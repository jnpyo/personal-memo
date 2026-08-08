package local.personalmemo.analysis.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.taxonomy.domain.TagNormalizer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reconstructs the mobile client's untouched review draft and compares it with the validated
 * selection stored by an analysis application. This comparison never changes canonical data.
 */
@Component
public final class AnalysisReviewOutcomeClassifier {
  public static final String POLICY_VERSION = "review-default-v1";

  private static final Set<String> ITEM_KINDS =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD");
  private static final Set<String> DATE_PRECISIONS =
      Set.of("EXACT_TIME", "DATE_ONLY", "RELATIVE_EXACT", "APPROXIMATE", "UNKNOWN");

  private final TagNormalizer tagNormalizer;
  private final AnalysisProposalSchemaValidator proposalSchemaValidator;

  public AnalysisReviewOutcomeClassifier(
      TagNormalizer tagNormalizer, AnalysisProposalSchemaValidator proposalSchemaValidator) {
    this.tagNormalizer = tagNormalizer;
    this.proposalSchemaValidator = proposalSchemaValidator;
  }

  public Classification classify(
      JsonNode proposal, JsonNode storedSelection, ReviewContext reviewContext) {
    try {
      // Validate the complete, current proposal contract before any projection can be called a
      // user resolution. Future or corrupted JSON must remain visible as unclassifiable instead
      // of silently looking like an accepted analyzer suggestion.
      proposalSchemaValidator.validate(proposal);
      if (!isObject(storedSelection)
          || !contextMatches(proposal, reviewContext)
          || !proposal.path("relationCandidates").isArray()
          || !proposal.path("relationCandidates").isEmpty()) {
        return Classification.unclassifiable();
      }

      SemanticSelection actual = selection(storedSelection);
      if (actual == null) {
        return Classification.unclassifiable();
      }

      if (actual.memoRevision() != reviewContext.runMemoRevision()) {
        return Classification.unclassifiable();
      }

      Projection projection = projection(proposal);
      if (projection.state() == ProjectionState.INVALID) {
        return Classification.unclassifiable();
      }
      if (projection.state() == ProjectionState.USER_RESOLUTION_REQUIRED) {
        return new Classification(Outcome.USER_RESOLVED, CorrectedFields.none());
      }

      SemanticSelection expected = projection.selection();
      CorrectedFields correctedFields = correctedFields(expected, actual);
      return correctedFields.any()
          ? new Classification(Outcome.CORRECTED, correctedFields)
          : new Classification(Outcome.EXACT, correctedFields);
    } catch (RuntimeException ignored) {
      // Historical/future JSON that cannot be reconstructed must not be guessed into a quality
      // bucket. The aggregate intentionally exposes this uncertainty.
      return Classification.unclassifiable();
    }
  }

  private boolean contextMatches(JsonNode proposal, ReviewContext context) {
    if (context == null
        || !"1".equals(context.runSchemaVersion())
        || context.runMemoId() == null
        || context.runMemoRevision() < 1
        || context.applicationMemoId() == null
        || context.applicationMemoRevision() < 1) {
      return false;
    }

    String proposalMemoId = text(proposal.path("memoId"));
    Integer proposalMemoRevision = positiveInteger(proposal.path("memoRevision"));
    return context.runSchemaVersion().equals(text(proposal.path("schemaVersion")))
        && context.runMemoId().toString().equals(proposalMemoId)
        && proposalMemoRevision != null
        && proposalMemoRevision == context.runMemoRevision()
        && context.runMemoId().equals(context.applicationMemoId())
        && context.runMemoRevision() == context.applicationMemoRevision();
  }

  private Projection projection(JsonNode proposal) {
    if (!"1".equals(text(proposal.path("schemaVersion")))) {
      return Projection.invalid();
    }
    Integer memoRevision = positiveInteger(proposal.path("memoRevision"));
    String title = strippedText(proposal.path("suggestedTitle").path("value"));
    if (memoRevision == null || title == null) {
      return Projection.invalid();
    }

    PreferredKind preferred = preferredKind(proposal.path("typeCandidates"));
    if (!preferred.valid()) {
      return Projection.invalid();
    }
    if (preferred.kind() == null) {
      return Projection.userResolutionRequired();
    }

    List<TagKey> tags = proposalTags(proposal.path("tagCandidates"));
    List<SemanticItem> items = proposalItems(proposal.path("itemCandidates"), title);
    if (tags == null || items == null) {
      return Projection.invalid();
    }
    if (items.isEmpty()) {
      return Projection.userResolutionRequired();
    }

    DueValue preferredDue = preferredDue(proposal.path("dateCandidates"));
    if (preferredDue == DueValue.INVALID) {
      return Projection.invalid();
    }
    boolean dueAssigned = false;
    List<SemanticItem> draftItems = new ArrayList<>();
    for (SemanticItem item : items) {
      DueValue due = null;
      if (!dueAssigned && "TASK".equals(item.kind()) && preferredDue != null) {
        due = preferredDue;
        dueAssigned = true;
      }
      draftItems.add(new SemanticItem(item.kind(), item.title(), due));
    }

    if (draftItems.stream().noneMatch(item -> preferred.kind().equals(item.kind()))) {
      SemanticItem first = draftItems.getFirst();
      draftItems.set(
          0,
          new SemanticItem(
              preferred.kind(),
              first.title(),
              "TASK".equals(preferred.kind()) ? first.due() : null));
    }

    SemanticSelection selection =
        new SemanticSelection(
            memoRevision, preferred.kind(), title, unorderedTags(tags), List.copyOf(draftItems));
    return validSelection(selection)
        ? Projection.ready(selection)
        : Projection.userResolutionRequired();
  }

  private SemanticSelection selection(JsonNode selection) {
    Integer memoRevision = positiveInteger(selection.path("expectedMemoRevision"));
    String selectedType = text(selection.path("selectedType"));
    String title = strippedText(selection.path("title"));
    if (memoRevision == null
        || !ITEM_KINDS.contains(selectedType)
        || title == null
        || !selection.path("selectedTags").isArray()
        || !selection.path("items").isArray()) {
      return null;
    }

    List<TagKey> tags = storedTags(selection.path("selectedTags"));
    List<SemanticItem> items = storedItems(selection.path("items"));
    if (tags == null || items == null) {
      return null;
    }

    SemanticSelection result =
        new SemanticSelection(memoRevision, selectedType, title, unorderedTags(tags), items);
    return validSelection(result) ? result : null;
  }

  private PreferredKind preferredKind(JsonNode candidates) {
    if (!candidates.isArray() || candidates.isEmpty()) {
      return PreferredKind.invalid();
    }
    String topValue = null;
    double topScore = Double.NEGATIVE_INFINITY;
    boolean tied = false;
    for (JsonNode candidate : candidates) {
      String value = text(candidate.path("value"));
      JsonNode scoreNode = candidate.path("score");
      if (value == null || !scoreNode.isNumber()) {
        return PreferredKind.invalid();
      }
      double score = scoreNode.doubleValue();
      if (!Double.isFinite(score)) {
        return PreferredKind.invalid();
      }
      if (score > topScore) {
        topValue = value;
        topScore = score;
        tied = false;
      } else if (Double.compare(score, topScore) == 0 && !Objects.equals(value, topValue)) {
        tied = true;
      }
    }
    return tied || !ITEM_KINDS.contains(topValue)
        ? PreferredKind.userResolutionRequired()
        : new PreferredKind(true, topValue);
  }

  private List<TagKey> proposalTags(JsonNode candidates) {
    if (!candidates.isArray()) {
      return null;
    }
    List<TagKey> tags = new ArrayList<>();
    for (JsonNode candidate : candidates) {
      JsonNode existing = candidate.path("existingTagId");
      if (existing.isTextual()) {
        tags.add(new TagKey("EXISTING", UUID.fromString(existing.asText()).toString()));
        continue;
      }
      if (!existing.isNull()) {
        return null;
      }
      String canonicalName = text(candidate.path("canonicalName"));
      if (canonicalName == null) {
        return null;
      }
      tags.add(new TagKey("NEW", tagNormalizer.normalize(canonicalName).normalizedName()));
    }
    return unique(tags) ? List.copyOf(tags) : null;
  }

  private List<TagKey> storedTags(JsonNode selectedTags) {
    if (selectedTags.size() > 10) {
      return null;
    }
    List<TagKey> tags = new ArrayList<>();
    for (JsonNode selectedTag : selectedTags) {
      JsonNode existing = selectedTag.path("existingTagId");
      if (existing.isTextual()) {
        if (!selectedTag.path("newCanonicalName").isNull()
            || !selectedTag.path("normalizedName").isNull()) {
          return null;
        }
        tags.add(new TagKey("EXISTING", UUID.fromString(existing.asText()).toString()));
        continue;
      }
      if (!existing.isNull()) {
        return null;
      }
      String canonicalName = text(selectedTag.path("newCanonicalName"));
      String normalizedName = text(selectedTag.path("normalizedName"));
      if (canonicalName == null || normalizedName == null) {
        return null;
      }
      String expectedNormalized = tagNormalizer.normalize(canonicalName).normalizedName();
      if (!expectedNormalized.equals(normalizedName)) {
        return null;
      }
      tags.add(new TagKey("NEW", normalizedName));
    }
    return unique(tags) ? List.copyOf(tags) : null;
  }

  private List<SemanticItem> proposalItems(JsonNode candidates, String suggestedTitle) {
    if (!candidates.isArray() || candidates.size() > 3) {
      return null;
    }
    List<SemanticItem> items = new ArrayList<>();
    for (int index = 0; index < candidates.size(); index++) {
      JsonNode candidate = candidates.get(index);
      String kind = text(candidate.path("kind"));
      String title = strippedText(candidate.path("title"));
      if (!ITEM_KINDS.contains(kind) || title == null) {
        return null;
      }
      items.add(new SemanticItem(kind, index == 0 ? suggestedTitle : title, null));
    }
    return List.copyOf(items);
  }

  private List<SemanticItem> storedItems(JsonNode itemsNode) {
    if (itemsNode.isEmpty() || itemsNode.size() > 3) {
      return null;
    }
    List<SemanticItem> items = new ArrayList<>();
    for (JsonNode item : itemsNode) {
      String kind = text(item.path("kind"));
      String title = strippedText(item.path("title"));
      if (!ITEM_KINDS.contains(kind) || title == null) {
        return null;
      }
      JsonNode dueNode = item.path("due");
      DueValue due = dueNode.isNull() ? null : storedDue(dueNode);
      if ((!dueNode.isNull() && due == null) || (!"TASK".equals(kind) && due != null)) {
        return null;
      }
      items.add(new SemanticItem(kind, title, due));
    }
    return List.copyOf(items);
  }

  private DueValue preferredDue(JsonNode candidates) {
    if (!candidates.isArray()) {
      return DueValue.INVALID;
    }
    DueValue first = null;
    DueValue firstDateOnly = null;
    for (JsonNode candidate : candidates) {
      DueValue due = proposalDue(candidate);
      if (due == null) {
        continue;
      }
      if (first == null) {
        first = due;
      }
      if (firstDateOnly == null && "DATE_ONLY".equals(due.precision())) {
        firstDateOnly = due;
      }
    }
    return firstDateOnly != null ? firstDateOnly : first;
  }

  private DueValue proposalDue(JsonNode due) {
    String surfaceText = strippedText(due.path("surfaceText"));
    String precision = text(due.path("precision"));
    JsonNode timeSpecifiedNode = due.path("timeSpecified");
    if (surfaceText == null
        || !DATE_PRECISIONS.contains(precision)
        || !timeSpecifiedNode.isBoolean()) {
      return null;
    }
    String value = nullableText(due.path("value"));
    NormalizedDue normalizedValue =
        normalizedDueValue(value, precision, timeSpecifiedNode.asBoolean());
    return normalizedValue.valid()
        ? new DueValue(
            surfaceText, normalizedValue.value(), precision, timeSpecifiedNode.asBoolean())
        : null;
  }

  private DueValue storedDue(JsonNode due) {
    if (!due.isObject()) {
      return null;
    }
    String surfaceText = strippedText(due.path("surfaceText"));
    String value = nullableText(due.path("originalValue"));
    String precision = text(due.path("precision"));
    String timeZone = text(due.path("timeZone"));
    JsonNode timeSpecifiedNode = due.path("timeSpecified");
    if (surfaceText == null
        || timeZone == null
        || !DATE_PRECISIONS.contains(precision)
        || !timeSpecifiedNode.isBoolean()) {
      return null;
    }
    NormalizedDue normalizedValue =
        normalizedDueValue(value, precision, timeSpecifiedNode.asBoolean());
    if (!normalizedValue.valid()
        || !storedDerivedDateIsConsistent(due, normalizedValue.value(), precision)) {
      return null;
    }
    return new DueValue(
        surfaceText, normalizedValue.value(), precision, timeSpecifiedNode.asBoolean());
  }

  private NormalizedDue normalizedDueValue(String value, String precision, boolean timeSpecified) {
    try {
      return switch (precision) {
        case "DATE_ONLY" -> {
          if (timeSpecified || value == null) {
            yield NormalizedDue.invalid();
          }
          yield NormalizedDue.valid(LocalDate.parse(value).toString());
        }
        case "EXACT_TIME", "RELATIVE_EXACT" -> {
          if (!timeSpecified || value == null) {
            yield NormalizedDue.invalid();
          }
          yield NormalizedDue.valid(OffsetDateTime.parse(value).toInstant().toString());
        }
        case "APPROXIMATE", "UNKNOWN" ->
            !timeSpecified && value == null ? NormalizedDue.valid(null) : NormalizedDue.invalid();
        default -> NormalizedDue.invalid();
      };
    } catch (DateTimeParseException exception) {
      return NormalizedDue.invalid();
    }
  }

  private boolean storedDerivedDateIsConsistent(
      JsonNode due, String normalizedValue, String precision) {
    JsonNode dueInstant = due.path("dueInstant");
    JsonNode dueLocalDate = due.path("dueLocalDate");
    try {
      return switch (precision) {
        case "DATE_ONLY" ->
            dueInstant.isNull()
                && dueLocalDate.isTextual()
                && LocalDate.parse(dueLocalDate.asText()).toString().equals(normalizedValue);
        case "EXACT_TIME", "RELATIVE_EXACT" ->
            dueLocalDate.isNull()
                && dueInstant.isTextual()
                && java.time.Instant.parse(dueInstant.asText()).toString().equals(normalizedValue);
        case "APPROXIMATE", "UNKNOWN" -> dueInstant.isNull() && dueLocalDate.isNull();
        default -> false;
      };
    } catch (DateTimeParseException exception) {
      return false;
    }
  }

  private CorrectedFields correctedFields(SemanticSelection expected, SemanticSelection actual) {
    boolean type = !expected.selectedType().equals(actual.selectedType());
    boolean title =
        !expected.title().equals(actual.title())
            || !expected.items().getFirst().title().equals(actual.items().getFirst().title());
    boolean tags = !expected.tags().equals(actual.tags());
    boolean items = itemShapeOrSecondaryTitleChanged(expected.items(), actual.items());
    boolean due = dueChanged(expected.items(), actual.items());
    return new CorrectedFields(type, title, tags, items, due);
  }

  private boolean itemShapeOrSecondaryTitleChanged(
      List<SemanticItem> expected, List<SemanticItem> actual) {
    if (expected.size() != actual.size()) {
      return true;
    }
    for (int index = 0; index < expected.size(); index++) {
      SemanticItem expectedItem = expected.get(index);
      SemanticItem actualItem = actual.get(index);
      if (!expectedItem.kind().equals(actualItem.kind())
          || (index > 0 && !expectedItem.title().equals(actualItem.title()))) {
        return true;
      }
    }
    return false;
  }

  private boolean dueChanged(List<SemanticItem> expected, List<SemanticItem> actual) {
    if (expected.size() != actual.size()) {
      return true;
    }
    for (int index = 0; index < expected.size(); index++) {
      if (!Objects.equals(expected.get(index).due(), actual.get(index).due())) {
        return true;
      }
    }
    return false;
  }

  private boolean validSelection(SemanticSelection selection) {
    return selection.memoRevision() > 0
        && ITEM_KINDS.contains(selection.selectedType())
        && !selection.title().isBlank()
        && !selection.items().isEmpty()
        && selection.items().size() <= 3
        && selection.items().getFirst().title().equals(selection.title())
        && selection.items().stream().anyMatch(item -> selection.selectedType().equals(item.kind()))
        && selection.items().stream()
            .allMatch(item -> "TASK".equals(item.kind()) || item.due() == null);
  }

  private Set<TagKey> unorderedTags(List<TagKey> tags) {
    return Set.copyOf(tags);
  }

  private boolean unique(List<TagKey> tags) {
    return new HashSet<>(tags).size() == tags.size();
  }

  private Integer positiveInteger(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1) {
      return null;
    }
    return node.intValue();
  }

  private String strippedText(JsonNode node) {
    String value = text(node);
    if (value == null) {
      return null;
    }
    String stripped = ecmaScriptTrim(value);
    return stripped.isEmpty() ? null : stripped;
  }

  /** Mirrors ECMAScript String.prototype.trim for review defaults created by the PWA. */
  private String ecmaScriptTrim(String value) {
    int start = 0;
    int end = value.length();
    while (start < end) {
      int codePoint = value.codePointAt(start);
      if (!isEcmaScriptTrimCodePoint(codePoint)) {
        break;
      }
      start += Character.charCount(codePoint);
    }
    while (end > start) {
      int codePoint = value.codePointBefore(end);
      if (!isEcmaScriptTrimCodePoint(codePoint)) {
        break;
      }
      end -= Character.charCount(codePoint);
    }
    return value.substring(start, end);
  }

  private boolean isEcmaScriptTrimCodePoint(int codePoint) {
    return codePoint == 0x0009
        || codePoint == 0x000A
        || codePoint == 0x000B
        || codePoint == 0x000C
        || codePoint == 0x000D
        || codePoint == 0x2028
        || codePoint == 0x2029
        || codePoint == 0xFEFF
        || Character.getType(codePoint) == Character.SPACE_SEPARATOR;
  }

  private String text(JsonNode node) {
    return node.isTextual() ? node.asText() : null;
  }

  private String nullableText(JsonNode node) {
    return node.isNull() ? null : text(node);
  }

  private boolean isObject(JsonNode node) {
    return node != null && node.isObject();
  }

  public enum Outcome {
    EXACT,
    CORRECTED,
    USER_RESOLVED,
    UNCLASSIFIABLE
  }

  public record Classification(Outcome outcome, CorrectedFields correctedFields) {
    static Classification unclassifiable() {
      return new Classification(Outcome.UNCLASSIFIABLE, CorrectedFields.none());
    }
  }

  public record CorrectedFields(
      boolean type, boolean title, boolean tags, boolean items, boolean due) {
    static CorrectedFields none() {
      return new CorrectedFields(false, false, false, false, false);
    }

    public boolean any() {
      return type || title || tags || items || due;
    }
  }

  public record ReviewContext(
      String runSchemaVersion,
      UUID runMemoId,
      int runMemoRevision,
      UUID applicationMemoId,
      int applicationMemoRevision) {}

  private record SemanticSelection(
      int memoRevision,
      String selectedType,
      String title,
      Set<TagKey> tags,
      List<SemanticItem> items) {}

  private record TagKey(String kind, String value) {}

  private record SemanticItem(String kind, String title, DueValue due) {}

  private record DueValue(
      String surfaceText, String value, String precision, boolean timeSpecified) {
    private static final DueValue INVALID = new DueValue("", null, "", false);
  }

  private record NormalizedDue(boolean valid, String value) {
    static NormalizedDue valid(String value) {
      return new NormalizedDue(true, value);
    }

    static NormalizedDue invalid() {
      return new NormalizedDue(false, null);
    }
  }

  private record PreferredKind(boolean valid, String kind) {
    static PreferredKind invalid() {
      return new PreferredKind(false, null);
    }

    static PreferredKind userResolutionRequired() {
      return new PreferredKind(true, null);
    }
  }

  private enum ProjectionState {
    READY,
    USER_RESOLUTION_REQUIRED,
    INVALID
  }

  private record Projection(ProjectionState state, SemanticSelection selection) {
    static Projection ready(SemanticSelection selection) {
      return new Projection(ProjectionState.READY, selection);
    }

    static Projection userResolutionRequired() {
      return new Projection(ProjectionState.USER_RESOLUTION_REQUIRED, null);
    }

    static Projection invalid() {
      return new Projection(ProjectionState.INVALID, null);
    }
  }
}
