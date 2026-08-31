package local.personalmemo.analysis.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.domain.TagRetrievalContext;
import local.personalmemo.analysis.domain.TagRetrievalContext.MatchKind;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.taxonomy.domain.TagNormalizer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Owner-scoped, exact canonical/alias lookup for bounded gateway context and safe resolution. */
@Component
public class OwnerTagContextRetriever {
  private static final int MAX_TAG_CANDIDATES = 10;
  private static final int ALIAS_PRIORITY = 0;
  private static final int CANONICAL_PRIORITY = 1;

  private final JdbcClient db;
  private final TagNormalizer tagNormalizer;

  public OwnerTagContextRetriever(JdbcClient db, TagNormalizer tagNormalizer) {
    this.db = db;
    this.tagNormalizer = tagNormalizer;
  }

  /**
   * Resolves proposal candidates from the complete bounded result before deriving a separate top-k
   * context. The proposal must already have passed structural schema validation.
   */
  public TagRetrievalContext resolve(UUID ownerId, ObjectNode proposal) {
    List<SourceCandidate> sources = sourceCandidates(proposal);
    LinkedHashSet<String> lookupTerms = new LinkedHashSet<>();
    for (SourceCandidate source : sources) {
      source.terms().forEach(term -> lookupTerms.add(term.normalizedValue()));
    }
    if (lookupTerms.isEmpty()) {
      return new TagRetrievalContext(TagRetrievalContext.CURRENT_VERSION, List.of());
    }

    List<TagMatch> matches = lookup(ownerId, List.copyOf(lookupTerms));
    resolveProposalCandidates(sources, matches);
    return context(sources, matches);
  }

  private List<SourceCandidate> sourceCandidates(ObjectNode proposal) {
    List<SourceCandidate> sources = new ArrayList<>();
    JsonNode candidates = proposal.path("tagCandidates");
    int boundedSize = Math.min(candidates.size(), MAX_TAG_CANDIDATES);
    for (int index = 0; index < boundedSize; index++) {
      JsonNode candidate = candidates.get(index);
      if (!candidate.path("isNewProposal").asBoolean()
          || !candidate.path("existingTagId").isNull()) {
        continue;
      }
      List<SourceTerm> terms = new ArrayList<>();
      normalize(candidate.path("matchedAlias"))
          .ifPresent(value -> terms.add(new SourceTerm(value, SourceKind.ALIAS)));
      normalize(candidate.path("canonicalName"))
          .filter(value -> terms.stream().noneMatch(term -> term.normalizedValue().equals(value)))
          .ifPresent(value -> terms.add(new SourceTerm(value, SourceKind.CANONICAL)));
      if (!terms.isEmpty()) {
        sources.add(
            new SourceCandidate(
                index,
                candidate.path("score").asDouble(),
                (ObjectNode) candidate,
                List.copyOf(terms)));
      }
    }
    return List.copyOf(sources);
  }

  private java.util.Optional<String> normalize(JsonNode value) {
    if (!value.isTextual()) {
      return java.util.Optional.empty();
    }
    try {
      return java.util.Optional.of(tagNormalizer.normalize(value.asText()).normalizedName());
    } catch (DomainException exception) {
      return java.util.Optional.empty();
    }
  }

  private List<TagMatch> lookup(UUID ownerId, List<String> terms) {
    return db.sql(
            """
            select matched.id,
                   matched.canonical_name,
                   matched.matched_normalized,
                   matched.matched_alias,
                   matched.match_kind
              from (
                select t.id,
                       t.canonical_name,
                       t.normalized_name as matched_normalized,
                       cast(null as varchar) as matched_alias,
                       'CANONICAL' as match_kind,
                       1 as match_priority
                  from tags t
                 where t.owner_id = :ownerId
                   and t.state = 'ACTIVE'
                   and t.normalized_name in (:terms)
                union all
                select t.id,
                       t.canonical_name,
                       ta.normalized_alias as matched_normalized,
                       ta.alias as matched_alias,
                       'ALIAS' as match_kind,
                       0 as match_priority
                  from tag_aliases ta
                  join tags t
                    on t.id = ta.tag_id
                   and t.owner_id = ta.owner_id
                 where ta.owner_id = :ownerId
                   and t.state = 'ACTIVE'
                   and ta.normalized_alias in (:terms)
              ) matched
             order by matched.matched_normalized,
                      matched.match_priority,
                      matched.canonical_name,
                      coalesce(matched.matched_alias, ''),
                      matched.id
            """)
        .param("ownerId", ownerId)
        .param("terms", terms)
        .query(
            (resultSet, rowNumber) ->
                new TagMatch(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("canonical_name"),
                    resultSet.getString("matched_normalized"),
                    resultSet.getString("matched_alias"),
                    MatchKind.valueOf(resultSet.getString("match_kind"))))
        .list();
  }

  private void resolveProposalCandidates(List<SourceCandidate> sources, List<TagMatch> matches) {
    for (SourceCandidate source : sources) {
      Set<String> sourceTerms = new HashSet<>();
      source.terms().forEach(term -> sourceTerms.add(term.normalizedValue()));
      LinkedHashMap<UUID, List<TagMatch>> matchesByTag = new LinkedHashMap<>();
      for (TagMatch match : matches) {
        if (sourceTerms.contains(match.matchedNormalized())) {
          matchesByTag.computeIfAbsent(match.id(), ignored -> new ArrayList<>()).add(match);
        }
      }
      if (matchesByTag.size() != 1) {
        continue;
      }
      List<TagMatch> uniqueTagMatches = matchesByTag.values().iterator().next();
      TagMatch resolved =
          uniqueTagMatches.stream().min(representativeComparator(source)).orElseThrow();
      source
          .proposalCandidate()
          .put("existingTagId", resolved.id().toString())
          .put("canonicalName", resolved.canonicalName())
          .put("isNewProposal", false);
      if (resolved.matchKind() == MatchKind.ALIAS) {
        source.proposalCandidate().put("matchedAlias", resolved.matchedAlias());
      } else {
        source.proposalCandidate().putNull("matchedAlias");
      }
    }
  }

  private Comparator<TagMatch> representativeComparator(SourceCandidate source) {
    Set<String> explicitAliases = new HashSet<>();
    source.terms().stream()
        .filter(term -> term.sourceKind() == SourceKind.ALIAS)
        .forEach(term -> explicitAliases.add(term.normalizedValue()));
    return Comparator.comparingInt(
            (TagMatch match) ->
                match.matchKind() == MatchKind.ALIAS
                        && explicitAliases.contains(match.matchedNormalized())
                    ? 0
                    : 1)
        .thenComparingInt(
            match -> match.matchKind() == MatchKind.ALIAS ? ALIAS_PRIORITY : CANONICAL_PRIORITY)
        .thenComparing(TagMatch::matchedNormalized)
        .thenComparing(TagMatch::canonicalName)
        .thenComparing(match -> match.matchedAlias() == null ? "" : match.matchedAlias())
        .thenComparing(match -> match.id().toString());
  }

  private TagRetrievalContext context(List<SourceCandidate> sources, List<TagMatch> matches) {
    List<ContextOption> options = new ArrayList<>();
    Map<String, List<TagMatch>> matchesByTerm = new LinkedHashMap<>();
    for (TagMatch match : matches) {
      matchesByTerm
          .computeIfAbsent(match.matchedNormalized(), ignored -> new ArrayList<>())
          .add(match);
    }
    for (SourceCandidate source : sources) {
      for (SourceTerm term : source.terms()) {
        for (TagMatch match : matchesByTerm.getOrDefault(term.normalizedValue(), List.of())) {
          options.add(new ContextOption(source, term, match));
        }
      }
    }
    options.sort(contextOptionComparator());
    LinkedHashMap<UUID, ContextOption> unique = new LinkedHashMap<>();
    for (ContextOption option : options) {
      unique.putIfAbsent(option.match().id(), option);
    }

    List<TagRetrievalContext.Candidate> candidates = new ArrayList<>();
    int rank = 1;
    for (ContextOption option : unique.values()) {
      if (rank > TagRetrievalContext.MAX_CANDIDATES) {
        break;
      }
      TagMatch match = option.match();
      candidates.add(
          new TagRetrievalContext.Candidate(
              rank,
              match.id(),
              match.canonicalName(),
              match.matchKind() == MatchKind.ALIAS ? match.matchedAlias() : null,
              match.matchKind(),
              option.source().index()));
      rank++;
    }
    return new TagRetrievalContext(TagRetrievalContext.CURRENT_VERSION, candidates);
  }

  private Comparator<ContextOption> contextOptionComparator() {
    return Comparator.comparingDouble((ContextOption option) -> option.source().score())
        .reversed()
        .thenComparingInt(option -> option.source().index())
        .thenComparingInt(
            option ->
                option.term().sourceKind() == SourceKind.ALIAS
                    ? ALIAS_PRIORITY
                    : CANONICAL_PRIORITY)
        .thenComparingInt(
            option ->
                option.match().matchKind() == MatchKind.ALIAS ? ALIAS_PRIORITY : CANONICAL_PRIORITY)
        .thenComparing(option -> option.term().normalizedValue())
        .thenComparing(option -> option.match().canonicalName())
        .thenComparing(
            option -> option.match().matchedAlias() == null ? "" : option.match().matchedAlias())
        .thenComparing(option -> option.match().id().toString());
  }

  private enum SourceKind {
    ALIAS,
    CANONICAL
  }

  private record SourceTerm(String normalizedValue, SourceKind sourceKind) {}

  private record SourceCandidate(
      int index, double score, ObjectNode proposalCandidate, List<SourceTerm> terms) {}

  private record TagMatch(
      UUID id,
      String canonicalName,
      String matchedNormalized,
      String matchedAlias,
      MatchKind matchKind) {}

  private record ContextOption(SourceCandidate source, SourceTerm term, TagMatch match) {}
}
