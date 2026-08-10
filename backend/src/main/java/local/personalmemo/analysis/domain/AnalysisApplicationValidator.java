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
    boolean selectedTypeIsRepresented =
        items.stream().anyMatch(item -> item.kind().equals(selectedType));
    if (!selectedTypeIsRepresented) {
      throw invalid(
          "SELECTED_TYPE_NOT_APPLIED", "selectedType must match at least one confirmed item kind.");
    }

    return new ValidatedApply(request.expectedMemoRevision(), selectedType, title, tags, items);
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
        items);
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
    for (Item item : request.items()) {
      String kind = requireItemKind(item.kind(), "items.kind");
      String itemTitle = boundedText(item.title(), 200, "items.title");
      ValidatedDue due = validateDue(item.due());
      if (due != null && !"TASK".equals(kind)) {
        throw invalid("DUE_REQUIRES_TASK", "Only a TASK item may contain a due value.");
      }
      items.add(new ValidatedItem(kind, itemTitle, due));
    }
    return List.copyOf(items);
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

  private DomainException invalid(String code, String message) {
    return DomainException.invalid(code, message);
  }

  public record ValidatedApply(
      int expectedMemoRevision,
      String selectedType,
      String title,
      List<ValidatedTag> selectedTags,
      List<ValidatedItem> items) {
    public ValidatedApply {
      selectedTags = List.copyOf(selectedTags);
      items = List.copyOf(items);
    }
  }

  public record ValidatedTag(UUID existingTagId, String newCanonicalName, String normalizedName) {}

  public record ValidatedItem(String kind, String title, ValidatedDue due) {}

  public record ValidatedDue(
      String surfaceText,
      String originalValue,
      String precision,
      String timeZone,
      boolean timeSpecified,
      Instant dueInstant,
      LocalDate dueLocalDate) {}
}
