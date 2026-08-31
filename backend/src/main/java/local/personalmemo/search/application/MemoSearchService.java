package local.personalmemo.search.application;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.security.Hashing;
import local.personalmemo.search.api.SearchDtos;
import local.personalmemo.search.infrastructure.MemoSearchRepository;
import local.personalmemo.search.infrastructure.MemoSearchRepository.Candidate;
import local.personalmemo.search.infrastructure.MemoSearchRepository.Criteria;
import local.personalmemo.taxonomy.domain.TagNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoSearchService {
  static final String SORT_SHAPE = "CURRENT_REVISION_RECENCY_V1";
  static final String DIGEST_SHAPE = "MEMO_LEXICAL_SEARCH_SNAPSHOT_V1";

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 50;
  private static final int MAX_QUERY_LENGTH = 200;
  private static final int MAX_PREVIEW_CODE_POINTS = 240;
  private static final int MAX_CANONICAL_TAGS = 8;
  private static final Duration CURSOR_MAX_AGE = Duration.ofHours(24);
  private static final Duration CURSOR_MAX_FUTURE_SKEW = Duration.ofMinutes(1);
  private static final Instant MIN_SEARCH_DATE = Instant.parse("0001-01-01T00:00:00Z");
  private static final Instant MAX_SEARCH_DATE = Instant.parse("9999-12-31T23:59:59.999999Z");
  private static final Set<String> LIFECYCLE_STATUSES = Set.of("ACTIVE", "TRASHED");
  private static final Set<String> TASK_STATES = Set.of("NONE", "TODO", "DONE", "CANCELLED");

  private final MemoSearchRepository repository;
  private final CurrentIdentity identity;
  private final TagNormalizer tagNormalizer;
  private final MemoSearchCursorCodec cursors;
  private final Clock clock;

  @Autowired
  public MemoSearchService(
      MemoSearchRepository repository,
      CurrentIdentity identity,
      TagNormalizer tagNormalizer,
      MemoSearchCursorCodec cursors) {
    this(repository, identity, tagNormalizer, cursors, Clock.systemUTC());
  }

  MemoSearchService(
      MemoSearchRepository repository,
      CurrentIdentity identity,
      TagNormalizer tagNormalizer,
      MemoSearchCursorCodec cursors,
      Clock clock) {
    this.repository = repository;
    this.identity = identity;
    this.tagNormalizer = tagNormalizer;
    this.cursors = cursors;
    this.clock = clock;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SearchDtos.Page search(SearchDtos.Request request) {
    NormalizedQuery query = normalizeQuery(request.query());
    Filters filters = validateFilters(request);
    int limit = validateLimit(request.limit());
    UUID ownerId = identity.ownerId();
    String queryDigest = Hashing.sha256(query.textQuery());
    String filterDigest = Hashing.sha256(filters.canonicalShape());

    MemoSearchCursorCodec.DecodedCursor decoded =
        request.cursor() == null
            ? null
            : cursors.decode(request.cursor(), ownerId, queryDigest, filterDigest, SORT_SHAPE);
    Instant now = clock.instant();
    Instant snapshotAsOf = decoded == null ? now : decoded.snapshotAsOf();
    if (decoded != null
        && (snapshotAsOf.isBefore(now.minus(CURSOR_MAX_AGE))
            || snapshotAsOf.isAfter(now.plus(CURSOR_MAX_FUTURE_SKEW)))) {
      throw invalidCursor();
    }

    Criteria criteria =
        new Criteria(
            ownerId,
            query.textQuery(),
            query.tagQueryEligible(),
            query.tagQuery(),
            filters.lifecycleStatus(),
            filters.taskState(),
            filters.overdue(),
            filters.revisedFrom(),
            filters.revisedBefore(),
            snapshotAsOf,
            queryDigest,
            filterDigest);

    String resultDigest = null;
    Candidate after = null;
    if (decoded != null) {
      resultDigest = digest(criteria, snapshotAsOf);
      if (!decoded.resultDigest().equals(resultDigest)) {
        throw invalidCursor();
      }
      after = repository.find(criteria, decoded.lastMemoId()).orElseThrow(this::invalidCursor);
    }

    List<Candidate> candidates = repository.page(criteria, after, limit + 1);
    boolean truncated = candidates.size() > limit;
    List<Candidate> selected =
        List.copyOf(candidates.subList(0, Math.min(candidates.size(), limit)));
    if (truncated && resultDigest == null) {
      resultDigest = digest(criteria, snapshotAsOf);
    }

    Map<UUID, List<SearchDtos.CanonicalTag>> tags =
        repository.canonicalTags(
            ownerId,
            selected.stream().map(Candidate::memoId).toList(),
            query.tagQueryEligible(),
            query.tagQuery(),
            MAX_CANONICAL_TAGS);
    List<SearchDtos.Item> items =
        selected.stream()
            .map(candidate -> item(candidate, tags.getOrDefault(candidate.memoId(), List.of())))
            .toList();
    String nextCursor =
        truncated
            ? cursors.encode(
                ownerId,
                queryDigest,
                filterDigest,
                SORT_SHAPE,
                snapshotAsOf,
                resultDigest,
                selected.getLast().memoId())
            : null;
    return new SearchDtos.Page(items, nextCursor, truncated);
  }

  private String digest(Criteria criteria, Instant snapshotAsOf) {
    return repository.digest(criteria, DIGEST_SHAPE, SORT_SHAPE, snapshotAsOf.toString());
  }

  private SearchDtos.Item item(Candidate candidate, List<SearchDtos.CanonicalTag> tags) {
    List<String> matchedFields = new ArrayList<>(4);
    if (candidate.titleMatch()) {
      matchedFields.add("TITLE");
    }
    if (candidate.bodyMatch()) {
      matchedFields.add("BODY");
    }
    if (candidate.tagMatch()) {
      matchedFields.add("TAG");
    }
    if (candidate.aliasMatch()) {
      matchedFields.add("ALIAS");
    }
    return new SearchDtos.Item(
        candidate.memoId(),
        candidate.currentRevision(),
        candidate.canonicalRevision(),
        candidate.title(),
        preview(candidate.content()),
        candidate.lifecycleStatus(),
        tags,
        candidate.taskState(),
        candidate.overdue(),
        candidate.pinned(),
        candidate.revisedAt(),
        matchedFields);
  }

  private String preview(String content) {
    int codePoints = content.codePointCount(0, content.length());
    if (codePoints <= MAX_PREVIEW_CODE_POINTS) {
      return content;
    }
    int end = content.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS - 1);
    return content.substring(0, end) + "…";
  }

  private NormalizedQuery normalizeQuery(String rawQuery) {
    if (rawQuery == null
        || rawQuery.length() > MAX_QUERY_LENGTH
        || rawQuery.indexOf('\0') >= 0
        || hasUnpairedSurrogate(rawQuery)) {
      throw invalidQuery();
    }
    String textQuery =
        Normalizer.normalize(rawQuery, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    int length = textQuery.codePointCount(0, textQuery.length());
    if (length < 1 || length > MAX_QUERY_LENGTH || textQuery.length() > MAX_QUERY_LENGTH) {
      throw invalidQuery();
    }

    try {
      String tagQuery = tagNormalizer.normalize(rawQuery).normalizedName();
      return new NormalizedQuery(textQuery, true, tagQuery);
    } catch (DomainException exception) {
      return new NormalizedQuery(textQuery, false, "");
    }
  }

  private boolean hasUnpairedSurrogate(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return true;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }

  private Filters validateFilters(SearchDtos.Request request) {
    String lifecycleStatus =
        request.lifecycleStatus() == null ? "ACTIVE" : request.lifecycleStatus();
    if (!LIFECYCLE_STATUSES.contains(lifecycleStatus)) {
      throw invalidFilters();
    }
    String taskState = request.taskState();
    if (taskState != null && !TASK_STATES.contains(taskState)) {
      throw invalidFilters();
    }
    if (Boolean.TRUE.equals(request.overdue()) && taskState != null && !"TODO".equals(taskState)) {
      throw invalidFilters();
    }
    if (!isSupportedSearchDate(request.revisedFrom())
        || !isSupportedSearchDate(request.revisedBefore())
        || (request.revisedFrom() != null
            && request.revisedBefore() != null
            && !request.revisedFrom().isBefore(request.revisedBefore()))) {
      throw invalidDateRange();
    }
    return new Filters(
        lifecycleStatus,
        taskState,
        request.overdue(),
        request.revisedFrom(),
        request.revisedBefore());
  }

  private int validateLimit(Integer requestedLimit) {
    int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
    if (limit < 1 || limit > MAX_LIMIT) {
      throw DomainException.invalid("INVALID_SEARCH_LIMIT", "limit must be between 1 and 50.");
    }
    return limit;
  }

  private DomainException invalidQuery() {
    return DomainException.invalid(
        "INVALID_SEARCH_QUERY", "query must contain between 1 and 200 characters.");
  }

  private DomainException invalidFilters() {
    return DomainException.invalid("INVALID_SEARCH_FILTERS", "The search filters are invalid.");
  }

  private boolean isSupportedSearchDate(Instant value) {
    return value == null || (!value.isBefore(MIN_SEARCH_DATE) && !value.isAfter(MAX_SEARCH_DATE));
  }

  private DomainException invalidDateRange() {
    return DomainException.invalid(
        "INVALID_SEARCH_DATE_RANGE",
        "Search dates must be between 0001-01-01T00:00:00Z and "
            + "9999-12-31T23:59:59.999999Z, and revisedFrom must be earlier than "
            + "revisedBefore.");
  }

  private DomainException invalidCursor() {
    return DomainException.invalid("INVALID_SEARCH_CURSOR", "The memo search cursor is invalid.");
  }

  private record NormalizedQuery(String textQuery, boolean tagQueryEligible, String tagQuery) {}

  private record Filters(
      String lifecycleStatus,
      String taskState,
      Boolean overdue,
      Instant revisedFrom,
      Instant revisedBefore) {
    String canonicalShape() {
      return String.join(
          "\n",
          "memo-search-filters-v1",
          lifecycleStatus,
          taskState == null ? "" : taskState,
          overdue == null ? "" : overdue.toString(),
          revisedFrom == null ? "" : revisedFrom.toString(),
          revisedBefore == null ? "" : revisedBefore.toString());
    }
  }
}
