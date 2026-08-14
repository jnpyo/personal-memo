package local.personalmemo.analysis.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.api.AnalysisDtos.Apply;
import local.personalmemo.analysis.api.AnalysisDtos.Due;
import local.personalmemo.analysis.api.AnalysisDtos.Item;
import local.personalmemo.analysis.api.AnalysisDtos.SelectedRelation;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.taxonomy.domain.TagNormalizer;
import org.springframework.stereotype.Component;

@Component
public class AnalysisApplicationValidator {
  private static final Set<String> ITEM_KINDS =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD");
  private static final Set<String> DATE_PRECISIONS =
      Set.of("EXACT_TIME", "DATE_ONLY", "RELATIVE_EXACT", "APPROXIMATE", "UNKNOWN");

  private final TagNormalizer tagNormalizer;

  public AnalysisApplicationValidator(TagNormalizer tagNormalizer) {
    this.tagNormalizer = tagNormalizer;
  }

  public ValidatedApply validate(Apply request) {
    String selectedType = requireItemKind(request.selectedType(), "selectedType");
    String title = boundedText(request.title(), 200, "title");

    List<ValidatedTag> tags = validateTags(request);
    List<ValidatedItem> items = validateItems(request);
    List<ValidatedSelectedRelation> selectedRelations = validateSelectedRelations(request);
    boolean selectedTypeIsRepresented =
        items.stream().anyMatch(item -> item.kind().equals(selectedType));
    if (!selectedTypeIsRepresented) {
      throw invalid(
          "SELECTED_TYPE_NOT_APPLIED", "selectedType must match at least one confirmed item kind.");
    }

    return new ValidatedApply(
        request.expectedMemoRevision(), selectedType, title, tags, items, selectedRelations);
  }

  public ValidatedApply canonicalizeDueTimeZone(ValidatedApply selection, String sourceTimeZone) {
    String canonicalTimeZone = requireTimeZone(sourceTimeZone, "memo source time zone");
    List<ValidatedItem> items =
        selection.items().stream()
            .map(
                item -> {
                  ValidatedDue due = item.due();
                  if (due == null) {
                    return item;
                  }
                  return new ValidatedItem(
                      item.proposalCandidateId(),
                      item.kind(),
                      item.title(),
                      new ValidatedDue(
                          due.surfaceText(),
                          due.originalValue(),
                          due.precision(),
                          canonicalTimeZone,
                          due.timeSpecified(),
                          due.dueInstant(),
                          due.dueLocalDate()));
                })
            .toList();
    return new ValidatedApply(
        selection.expectedMemoRevision(),
        selection.selectedType(),
        selection.title(),
        selection.selectedTags(),
        items,
        selection.selectedRelations());
  }

  private List<ValidatedTag> validateTags(Apply request) {
    List<ValidatedTag> tags = new ArrayList<>();
    Set<UUID> existingIds = new HashSet<>();
    Set<String> normalizedNames = new HashSet<>();

    for (var tag : request.selectedTags()) {
      boolean hasExisting = tag.existingTagId() != null;
      boolean hasNew = tag.newCanonicalName() != null && !tag.newCanonicalName().isBlank();
      if (hasExisting == hasNew) {
        throw invalid(
            "INVALID_TAG_SELECTION",
            "Each selected tag must contain exactly one existingTagId or newCanonicalName.");
      }

      if (hasExisting) {
        if (!existingIds.add(tag.existingTagId())) {
          throw invalid("DUPLICATE_TAG_SELECTION", "A tag can only be selected once.");
        }
        tags.add(new ValidatedTag(tag.existingTagId(), null, null));
        continue;
      }

      TagNormalizer.NormalizedTag normalized = tagNormalizer.normalize(tag.newCanonicalName());
      if (!normalizedNames.add(normalized.normalizedName())) {
        throw invalid("DUPLICATE_TAG_SELECTION", "A tag can only be selected once.");
      }
      tags.add(new ValidatedTag(null, normalized.canonicalName(), normalized.normalizedName()));
    }
    return List.copyOf(tags);
  }

  private List<ValidatedItem> validateItems(Apply request) {
    List<ValidatedItem> items = new ArrayList<>();
    Set<String> proposalCandidateIds = new HashSet<>();
    for (Item item : request.items()) {
      String proposalCandidateId = optionalCandidateId(item.proposalCandidateId());
      if (proposalCandidateId != null && !proposalCandidateIds.add(proposalCandidateId)) {
        throw invalid(
            "INVALID_RELATION_SELECTION",
            "Each proposalCandidateId can map to at most one applied item.");
      }
      String kind = requireItemKind(item.kind(), "items.kind");
      String itemTitle = boundedText(item.title(), 200, "items.title");
      ValidatedDue due = validateDue(item.due());
      if (due != null && !"TASK".equals(kind)) {
        throw invalid("DUE_REQUIRES_TASK", "Only a TASK item may contain a due value.");
      }
      items.add(new ValidatedItem(proposalCandidateId, kind, itemTitle, due));
    }
    return List.copyOf(items);
  }

  private List<ValidatedSelectedRelation> validateSelectedRelations(Apply request) {
    if (request.selectedRelations() == null) {
      return null;
    }
    List<ValidatedSelectedRelation> relations = new ArrayList<>();
    Set<Integer> proposalIndexes = new HashSet<>();
    for (SelectedRelation relation : request.selectedRelations()) {
      if (relation == null || relation.proposalIndex() == null) {
        throw invalid(
            "INVALID_RELATION_SELECTION", "Each selected relation requires a proposalIndex.");
      }
      int proposalIndex = relation.proposalIndex();
      if (proposalIndex < 0 || proposalIndex > 9 || !proposalIndexes.add(proposalIndex)) {
        throw invalid(
            "INVALID_RELATION_SELECTION",
            "Each proposal relation index must be unique and between 0 and 9.");
      }
      relations.add(new ValidatedSelectedRelation(proposalIndex));
    }
    return List.copyOf(relations);
  }

  private ValidatedDue validateDue(Due due) {
    if (due == null) {
      return null;
    }

    String surfaceText = boundedText(due.surfaceText(), 100, "items.due.surfaceText");
    if (!DATE_PRECISIONS.contains(due.precision())) {
      throw invalid("INVALID_DATE_PRECISION", "The due date precision is not supported.");
    }
    requireTimeZone(due.timeZone(), "items.due.timeZone");

    return switch (due.precision()) {
      case "DATE_ONLY" -> validateDateOnly(due, surfaceText);
      case "EXACT_TIME", "RELATIVE_EXACT" -> validateExactTime(due, surfaceText);
      case "APPROXIMATE", "UNKNOWN" -> validateImprecise(due, surfaceText);
      default -> throw new IllegalStateException("Unexpected date precision.");
    };
  }

  private ValidatedDue validateDateOnly(Due due, String surfaceText) {
    if (due.timeSpecified()) {
      throw invalid(
          "INVALID_DATE_VALUE", "DATE_ONLY cannot claim that a time was explicitly specified.");
    }
    try {
      LocalDate date = LocalDate.parse(requiredValue(due.value()));
      return new ValidatedDue(
          surfaceText, due.value(), due.precision(), due.timeZone(), false, null, date);
    } catch (DateTimeParseException exception) {
      throw invalid("INVALID_DATE_VALUE", "DATE_ONLY must use an ISO-8601 calendar date.");
    }
  }

  private ValidatedDue validateExactTime(Due due, String surfaceText) {
    if (!due.timeSpecified()) {
      throw invalid("INVALID_DATE_VALUE", "An exact due instant must explicitly include a time.");
    }
    try {
      Instant instant = OffsetDateTime.parse(requiredValue(due.value())).toInstant();
      return new ValidatedDue(
          surfaceText, due.value(), due.precision(), due.timeZone(), true, instant, null);
    } catch (DateTimeParseException exception) {
      throw invalid(
          "INVALID_DATE_VALUE", "An exact due value must be an ISO-8601 timestamp with an offset.");
    }
  }

  private ValidatedDue validateImprecise(Due due, String surfaceText) {
    if ((due.value() != null && !due.value().isBlank()) || due.timeSpecified()) {
      throw invalid(
          "INVALID_DATE_VALUE",
          "An approximate or unknown due expression cannot be stored as an exact date or time.");
    }
    return new ValidatedDue(surfaceText, null, due.precision(), due.timeZone(), false, null, null);
  }

  private String requireTimeZone(String timeZone, String field) {
    if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
      throw invalid("INVALID_TIME_ZONE", field + " must be a recognized IANA identifier.");
    }
    return timeZone;
  }

  private String requireItemKind(String rawKind, String field) {
    if (!ITEM_KINDS.contains(rawKind)) {
      throw invalid("INVALID_ITEM_KIND", field + " contains an unsupported item kind.");
    }
    return rawKind;
  }

  private String boundedText(String raw, int maximumLength, String field) {
    if (raw == null) {
      throw invalid("INVALID_TEXT", field + " is required.");
    }
    String value = raw.strip();
    if (value.isEmpty() || value.length() > maximumLength) {
      throw invalid(
          "INVALID_TEXT", field + " must contain between 1 and " + maximumLength + " characters.");
    }
    return value;
  }

  private String requiredValue(String value) {
    if (value == null || value.isBlank()) {
      throw invalid("INVALID_DATE_VALUE", "A date value is required for this precision.");
    }
    return value;
  }

  private String optionalCandidateId(String raw) {
    if (raw == null) {
      return null;
    }
    int codePointCount = raw.codePointCount(0, raw.length());
    if (raw.isBlank()
        || codePointCount > 100
        || raw.indexOf('\0') >= 0
        || hasUnpairedSurrogate(raw)) {
      throw invalid(
          "INVALID_RELATION_SELECTION",
          "proposalCandidateId must be a non-blank, well-formed identity of at most 100 Unicode code points.");
    }
    return raw;
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

  private DomainException invalid(String code, String message) {
    return DomainException.invalid(code, message);
  }

  public record ValidatedApply(
      int expectedMemoRevision,
      String selectedType,
      String title,
      List<ValidatedTag> selectedTags,
      List<ValidatedItem> items,
      List<ValidatedSelectedRelation> selectedRelations) {
    public ValidatedApply {
      selectedTags = List.copyOf(selectedTags);
      items = List.copyOf(items);
      selectedRelations = selectedRelations == null ? null : List.copyOf(selectedRelations);
    }
  }

  public record ValidatedTag(UUID existingTagId, String newCanonicalName, String normalizedName) {}

  public record ValidatedItem(
      String proposalCandidateId, String kind, String title, ValidatedDue due) {}

  public record ValidatedSelectedRelation(int proposalIndex) {}

  public record ValidatedDue(
      String surfaceText,
      String originalValue,
      String precision,
      String timeZone,
      boolean timeSpecified,
      Instant dueInstant,
      LocalDate dueLocalDate) {}
}
