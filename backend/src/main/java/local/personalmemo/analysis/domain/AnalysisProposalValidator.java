package local.personalmemo.analysis.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class AnalysisProposalValidator {
  private static final Set<String> ITEM_KINDS =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD");
  private static final Set<String> TYPE_CANDIDATES =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD", "UNKNOWN");
  private static final Set<String> DATE_PRECISIONS =
      Set.of("EXACT_TIME", "DATE_ONLY", "RELATIVE_EXACT", "APPROXIMATE", "UNKNOWN");
  private static final Set<String> AMBIGUITY_REASONS =
      Set.of(
          "LOW_TYPE_MARGIN",
          "LOW_TAG_SIMILARITY",
          "TAG_CONFLICT",
          "NEW_TOPIC",
          "MISSING_YEAR",
          "MISSING_TIME",
          "IMPRECISE_DATE",
          "CONFLICTING_DATES",
          "UNRESOLVED_REFERENCE",
          "MISSING_ACTION",
          "MISSING_OBJECT",
          "MULTI_INTENT",
          "LOCAL_CLOUD_CONFLICT");

  public void validate(JsonNode proposal, UUID memoId, int memoRevision, int contentLength) {
    requireObject(proposal, "proposal");
    requireText(proposal, "schemaVersion", 1, 16);
    if (!"1".equals(proposal.path("schemaVersion").asText())) {
      fail("schemaVersion must be 1.");
    }
    if (!memoId.equals(parseUuid(requireText(proposal, "memoId", 1, 64), "memoId"))) {
      fail("memoId does not match the analyzed memo.");
    }
    if (!proposal.path("memoRevision").isIntegralNumber()
        || proposal.path("memoRevision").asInt() != memoRevision) {
      fail("memoRevision does not match the analyzed revision.");
    }

    validateSuggestedTitle(proposal.path("suggestedTitle"));
    validateTypeCandidates(proposal.path("typeCandidates"));
    validateDateCandidates(proposal.path("dateCandidates"));
    validateTagCandidates(proposal.path("tagCandidates"));
    Set<String> itemCandidateIds =
        validateItemCandidates(proposal.path("itemCandidates"), contentLength);
    validateRelationCandidates(proposal.path("relationCandidates"), itemCandidateIds);
    validateAmbiguityReasons(proposal.path("ambiguityReasons"), "ambiguityReasons");
    requireObject(proposal.path("providerMetadata"), "providerMetadata");
  }

  private void validateSuggestedTitle(JsonNode title) {
    requireObject(title, "suggestedTitle");
    requireText(title, "value", 1, 200);
    requireScore(title.path("confidence"), "suggestedTitle.confidence");
    if (!title.path("needsConfirmation").isBoolean()) {
      fail("suggestedTitle.needsConfirmation must be a boolean.");
    }
  }

  private void validateTypeCandidates(JsonNode candidates) {
    requireArray(candidates, "typeCandidates", 1, 5);
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "typeCandidates[]");
      String value = requireText(candidate, "value", 1, 32);
      if (!TYPE_CANDIDATES.contains(value)) {
        fail("typeCandidates[] contains an unsupported value.");
      }
      requireScore(candidate.path("score"), "typeCandidates[].score");
    }
  }

  private void validateDateCandidates(JsonNode candidates) {
    requireArray(candidates, "dateCandidates", 0, 5);
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "dateCandidates[]");
      requireText(candidate, "surfaceText", 1, 200);
      String precision = requireText(candidate, "precision", 1, 32);
      if (!DATE_PRECISIONS.contains(precision)) {
        fail("dateCandidates[] contains an unsupported precision.");
      }
      if (!candidate.path("timeSpecified").isBoolean()) {
        fail("dateCandidates[].timeSpecified must be a boolean.");
      }
      requireScore(candidate.path("confidence"), "dateCandidates[].confidence");
      validateAmbiguityReasons(
          candidate.path("ambiguityReasons"), "dateCandidates[].ambiguityReasons");
      validateCandidateDateValue(candidate.path("value"), precision);
    }
  }

  private void validateCandidateDateValue(JsonNode value, String precision) {
    if (value.isNull()) {
      return;
    }
    if (!value.isTextual()) {
      fail("dateCandidates[].value must be a string or null.");
    }
    try {
      if ("DATE_ONLY".equals(precision)) {
        LocalDate.parse(value.asText());
      } else if ("EXACT_TIME".equals(precision) || "RELATIVE_EXACT".equals(precision)) {
        OffsetDateTime.parse(value.asText());
      }
    } catch (DateTimeParseException exception) {
      fail("dateCandidates[].value is impossible for its precision.");
    }
  }

  private void validateTagCandidates(JsonNode candidates) {
    requireArray(candidates, "tagCandidates", 0, 10);
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "tagCandidates[]");
      JsonNode existingTagId = candidate.path("existingTagId");
      if (!existingTagId.isNull() && !existingTagId.isTextual()) {
        fail("tagCandidates[].existingTagId must be a UUID or null.");
      }
      if (existingTagId.isTextual()) {
        parseUuid(existingTagId.asText(), "tagCandidates[].existingTagId");
      }
      requireText(candidate, "canonicalName", 1, 100);
      JsonNode matchedAlias = candidate.path("matchedAlias");
      if (!matchedAlias.isNull() && !matchedAlias.isTextual()) {
        fail("tagCandidates[].matchedAlias must be a string or null.");
      }
      requireScore(candidate.path("score"), "tagCandidates[].score");
      if (!candidate.path("isNewProposal").isBoolean()) {
        fail("tagCandidates[].isNewProposal must be a boolean.");
      }
      boolean newProposal = candidate.path("isNewProposal").asBoolean();
      if (newProposal == existingTagId.isTextual()) {
        fail("tagCandidates[] has inconsistent existing/new tag identity.");
      }
    }
  }

  private Set<String> validateItemCandidates(JsonNode candidates, int contentLength) {
    requireArray(candidates, "itemCandidates", 0, 3);
    Set<String> candidateIds = new HashSet<>();
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "itemCandidates[]");
      String candidateId = requireText(candidate, "candidateId", 1, 100);
      if (!candidateIds.add(candidateId)) {
        fail("itemCandidates[].candidateId must be unique.");
      }
      String kind = requireText(candidate, "kind", 1, 32);
      if (!ITEM_KINDS.contains(kind)) {
        fail("itemCandidates[] contains an unsupported kind.");
      }
      requireText(candidate, "title", 1, 200);
      validateSourceSpan(candidate.path("sourceSpan"), contentLength);
      requireNullableText(candidate.path("action"), "itemCandidates[].action");
      requireNullableText(candidate.path("object"), "itemCandidates[].object");
      requireScore(candidate.path("confidence"), "itemCandidates[].confidence");
    }
    return candidateIds;
  }

  private void validateSourceSpan(JsonNode sourceSpan, int contentLength) {
    if (sourceSpan.isNull()) {
      return;
    }
    requireObject(sourceSpan, "itemCandidates[].sourceSpan");
    JsonNode start = sourceSpan.path("start");
    JsonNode end = sourceSpan.path("end");
    if (!start.isIntegralNumber() || !end.isIntegralNumber()) {
      fail("itemCandidates[].sourceSpan must contain integer offsets.");
    }
    if (start.asInt() < 0 || end.asInt() < start.asInt() || end.asInt() > contentLength) {
      fail("itemCandidates[].sourceSpan is outside the memo content.");
    }
  }

  private void validateRelationCandidates(JsonNode candidates, Set<String> itemCandidateIds) {
    requireArray(candidates, "relationCandidates", 0, 10);
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "relationCandidates[]");
      String sourceCandidateId = requireText(candidate, "sourceCandidateId", 1, 100);
      if (!itemCandidateIds.contains(sourceCandidateId)) {
        fail("relationCandidates[] references an unknown source candidate.");
      }
      String targetType = requireText(candidate, "targetType", 1, 16);
      if (!Set.of("MEMO", "TAG").contains(targetType)) {
        fail("relationCandidates[] contains an unsupported targetType.");
      }
      parseUuid(requireText(candidate, "targetId", 1, 64), "relationCandidates[].targetId");
      String relationType = requireText(candidate, "relationType", 1, 32);
      if (!Set.of("RELATED_TO", "CONTINUES", "DEPENDS_ON", "REFERENCES")
          .contains(relationType)) {
        fail("relationCandidates[] contains an unsupported relationType.");
      }
      requireScore(candidate.path("score"), "relationCandidates[].score");
    }
  }

  private void validateAmbiguityReasons(JsonNode reasons, String field) {
    requireArray(reasons, field, 0, AMBIGUITY_REASONS.size());
    Set<String> uniqueReasons = new HashSet<>();
    for (JsonNode reason : reasons) {
      if (!reason.isTextual() || !AMBIGUITY_REASONS.contains(reason.asText())) {
        fail(field + " contains an unsupported reason.");
      }
      if (!uniqueReasons.add(reason.asText())) {
        fail(field + " must not contain duplicates.");
      }
    }
  }

  private void requireScore(JsonNode score, String field) {
    if (!score.isNumber()) {
      fail(field + " must be numeric.");
    }
    double value = score.asDouble();
    if (!Double.isFinite(value) || value < 0 || value > 1) {
      fail(field + " must be between 0 and 1.");
    }
  }

  private String requireText(JsonNode parent, String field, int minimum, int maximum) {
    JsonNode value = parent.path(field);
    if (!value.isTextual()) {
      fail(field + " must be text.");
    }
    String text = value.asText();
    if (text.isBlank() || text.length() < minimum || text.length() > maximum) {
      fail(field + " has an invalid length.");
    }
    return text;
  }

  private void requireNullableText(JsonNode value, String field) {
    if (!value.isNull() && !value.isTextual()) {
      fail(field + " must be text or null.");
    }
  }

  private void requireObject(JsonNode value, String field) {
    if (!value.isObject()) {
      fail(field + " must be an object.");
    }
  }

  private void requireArray(JsonNode value, String field, int minimum, int maximum) {
    if (!value.isArray() || value.size() < minimum || value.size() > maximum) {
      fail(field + " has an invalid number of values.");
    }
  }

  private UUID parseUuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      fail(field + " must be a UUID.");
      throw new IllegalStateException("Unreachable");
    }
  }

  private void fail(String reason) {
    throw DomainException.invalid("INVALID_ANALYSIS_PROPOSAL", reason);
  }
}
