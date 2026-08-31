package local.personalmemo.analysis.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class AnalysisProposalValidator {
  private static final int MAX_PROVIDER_TOOL_CALLS = 100;
  private static final Set<String> PROPOSAL_FIELDS =
      Set.of(
          "schemaVersion",
          "memoId",
          "memoRevision",
          "suggestedTitle",
          "typeCandidates",
          "dateCandidates",
          "tagCandidates",
          "itemCandidates",
          "relationCandidates",
          "ambiguityReasons",
          "providerMetadata");
  private static final Set<String> SCORED_TITLE_FIELDS =
      Set.of("value", "confidence", "needsConfirmation");
  private static final Set<String> TYPE_CANDIDATE_FIELDS = Set.of("value", "score");
  private static final Set<String> DATE_CANDIDATE_FIELDS =
      Set.of(
          "candidateId",
          "surfaceText",
          "value",
          "precision",
          "timeSpecified",
          "confidence",
          "ambiguityReasons");
  private static final Set<String> TAG_CANDIDATE_FIELDS =
      Set.of("existingTagId", "canonicalName", "matchedAlias", "score", "isNewProposal");
  private static final Set<String> ITEM_CANDIDATE_FIELDS =
      Set.of(
          "candidateId",
          "dueDateCandidateId",
          "eventScheduleCandidates",
          "suggestedEventScheduleCandidateId",
          "kind",
          "title",
          "sourceSpan",
          "action",
          "object",
          "confidence");
  private static final Set<String> SOURCE_SPAN_FIELDS = Set.of("start", "end");
  private static final Set<String> EVENT_SCHEDULE_CANDIDATE_FIELDS =
      Set.of("candidateId", "mode", "startDateCandidateId", "end", "score");
  private static final Set<String> EVENT_SCHEDULE_END_FIELDS =
      Set.of("dateCandidateId", "boundary");
  private static final Set<String> RELATION_CANDIDATE_FIELDS =
      Set.of("sourceCandidateId", "targetType", "targetId", "relationType", "score");
  private static final Set<String> ITEM_KINDS =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD");
  private static final Set<String> TYPE_CANDIDATES =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD", "UNKNOWN");
  private static final Set<String> DATE_PRECISIONS =
      Set.of("EXACT_TIME", "DATE_ONLY", "RELATIVE_EXACT", "APPROXIMATE", "UNKNOWN");
  private static final Set<String> EVENT_SCHEDULE_MODES = Set.of("TIMED", "ALL_DAY");
  private static final Set<String> EVENT_SCHEDULE_END_BOUNDARIES =
      Set.of("EXCLUSIVE_AT_VALUE", "INCLUSIVE_THROUGH_VALUE");
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
          "CANDIDATE_LIMIT_EXCEEDED",
          "LOCAL_CLOUD_CONFLICT");

  public void validate(JsonNode proposal, UUID memoId, int memoRevision, int contentLength) {
    requireObject(proposal, "proposal");
    rejectUnknownFields(proposal, "proposal", PROPOSAL_FIELDS);
    String schemaVersion = requireText(proposal, "schemaVersion", 1, 16);
    if (!Set.of("1", "2", "3").contains(schemaVersion)) {
      fail("schemaVersion must be 1, 2, or 3.");
    }
    if (!memoId.equals(parseUuid(requireText(proposal, "memoId", 1, 64), "memoId"))) {
      fail("memoId does not match the analyzed memo.");
    }
    JsonNode revision = proposal.path("memoRevision");
    if (!revision.isIntegralNumber()
        || !revision.canConvertToInt()
        || revision.asInt() < 1
        || revision.asInt() != memoRevision) {
      fail("memoRevision does not match the analyzed revision.");
    }

    validateSuggestedTitle(proposal.path("suggestedTitle"));
    validateTypeCandidates(proposal.path("typeCandidates"));
    ValidatedDateCandidates dates =
        validateDateCandidates(proposal.path("dateCandidates"), schemaVersion);
    validateTagCandidates(proposal.path("tagCandidates"));
    validateAmbiguityReasons(proposal.path("ambiguityReasons"), "ambiguityReasons");
    Set<String> itemCandidateIds =
        validateItemCandidates(
            proposal.path("itemCandidates"),
            contentLength,
            schemaVersion,
            dates,
            reasonValues(proposal.path("ambiguityReasons")));
    validateRelationCandidates(proposal.path("relationCandidates"), itemCandidateIds);
    validateAmbiguityConsistency(proposal);
    validateProviderMetadata(proposal.path("providerMetadata"));
  }

  public void validate(
      JsonNode proposal,
      UUID memoId,
      int memoRevision,
      int contentLength,
      AnalysisProvenance expectedProvenance) {
    validate(proposal, memoId, memoRevision, contentLength);
    validateExpectedProvenance(
        proposal.path("providerMetadata"),
        Objects.requireNonNull(expectedProvenance, "expectedProvenance"));
  }

  private void validateProviderMetadata(JsonNode metadata) {
    requireObject(metadata, "providerMetadata");
    requireText(metadata, "analyzerVersion", 1, AnalysisProvenance.MAX_VERSION_LENGTH);
    requireText(metadata, "promptVersion", 1, AnalysisProvenance.MAX_VERSION_LENGTH);
    requireText(metadata, "localModelVersion", 1, AnalysisProvenance.MAX_VERSION_LENGTH);
    requireText(metadata, "embeddingModelVersion", 1, AnalysisProvenance.MAX_VERSION_LENGTH);
    requireText(metadata, "routingPolicyVersion", 1, AnalysisProvenance.MAX_VERSION_LENGTH);
    JsonNode toolCalls = metadata.path("toolCalls");
    if (!toolCalls.isIntegralNumber()
        || !toolCalls.canConvertToInt()
        || toolCalls.asInt() < 0
        || toolCalls.asInt() > MAX_PROVIDER_TOOL_CALLS) {
      fail("providerMetadata.toolCalls must be an integer between 0 and 100.");
    }
  }

  private void validateExpectedProvenance(
      JsonNode metadata, AnalysisProvenance expectedProvenance) {
    if (!expectedProvenance.analyzerVersion().equals(metadata.path("analyzerVersion").asText())
        || !expectedProvenance.promptVersion().equals(metadata.path("promptVersion").asText())
        || !expectedProvenance
            .localModelVersion()
            .equals(metadata.path("localModelVersion").asText())
        || !expectedProvenance
            .embeddingModelVersion()
            .equals(metadata.path("embeddingModelVersion").asText())) {
      fail("Provider metadata does not match the server-owned analysis provenance.");
    }
  }

  public void validate(
      JsonNode proposal,
      UUID memoId,
      int memoRevision,
      int contentLength,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion) {
    validate(proposal, memoId, memoRevision, contentLength, expectedProvenance);
    if (expectedRoutingPolicyVersion == null
        || !expectedRoutingPolicyVersion.equals(
            proposal.path("providerMetadata").path("routingPolicyVersion").asText())) {
      fail("Provider metadata does not match the server-owned routing policy provenance.");
    }
  }

  public void validate(
      JsonNode proposal,
      UUID memoId,
      int memoRevision,
      String content,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion) {
    Objects.requireNonNull(content, "content");
    validate(
        proposal,
        memoId,
        memoRevision,
        content.length(),
        expectedProvenance,
        expectedRoutingPolicyVersion);
    validateSourceSpanBoundaries(proposal.path("itemCandidates"), content);
  }

  private void validateSourceSpanBoundaries(JsonNode candidates, String content) {
    for (JsonNode candidate : candidates) {
      JsonNode sourceSpan = candidate.path("sourceSpan");
      if (sourceSpan.isNull()) {
        continue;
      }
      int start = sourceSpan.path("start").asInt();
      int end = sourceSpan.path("end").asInt();
      if (splitsSurrogatePair(content, start) || splitsSurrogatePair(content, end)) {
        fail("itemCandidates[].sourceSpan must align to UTF-16 code point boundaries.");
      }
    }
  }

  private boolean splitsSurrogatePair(String content, int offset) {
    return offset > 0
        && offset < content.length()
        && Character.isHighSurrogate(content.charAt(offset - 1))
        && Character.isLowSurrogate(content.charAt(offset));
  }

  private void validateAmbiguityConsistency(JsonNode proposal) {
    Set<String> summary = reasonValues(proposal.path("ambiguityReasons"));
    for (JsonNode date : proposal.path("dateCandidates")) {
      if (!summary.containsAll(reasonValues(date.path("ambiguityReasons")))) {
        fail("Date ambiguity reasons must also appear in the proposal summary.");
      }
      if (Set.of("APPROXIMATE", "UNKNOWN").contains(date.path("precision").asText())
          && !summary.contains("IMPRECISE_DATE")) {
        fail("An imprecise date must be identified in the proposal summary.");
      }
    }
    for (JsonNode type : proposal.path("typeCandidates")) {
      if ("UNKNOWN".equals(type.path("value").asText())
          && !summary.contains("MISSING_ACTION")
          && !summary.contains("LOW_TYPE_MARGIN")) {
        fail("An unknown type must carry a critical ambiguity reason.");
      }
    }
    for (JsonNode tag : proposal.path("tagCandidates")) {
      if (tag.path("isNewProposal").asBoolean() && !summary.contains("NEW_TOPIC")) {
        fail("A new tag proposal must be identified as a new topic.");
      }
    }
    JsonNode items = proposal.path("itemCandidates");
    String topType = topType(proposal.path("typeCandidates"));
    if ("TASK".equals(topType) && items.isEmpty() && !summary.contains("MISSING_ACTION")) {
      fail("A task proposal without items must carry MISSING_ACTION.");
    }
    if (topType != null && !"UNKNOWN".equals(topType) && !items.isEmpty()) {
      boolean represented = false;
      for (JsonNode item : items) {
        if (topType.equals(item.path("kind").asText())) {
          represented = true;
          break;
        }
      }
      if (!represented && !summary.contains("LOCAL_CLOUD_CONFLICT")) {
        fail("The proposed type must agree with at least one item kind.");
      }
    }
    if (items.size() > 1 && !summary.contains("MULTI_INTENT")) {
      fail("Multiple item candidates must be identified as multiple intents.");
    }
    for (JsonNode item : items) {
      if (!"TASK".equals(item.path("kind").asText())) {
        continue;
      }
      if ((!item.path("action").isTextual() || item.path("action").asText().isBlank())
          && !summary.contains("MISSING_ACTION")) {
        fail("A task without an action must carry MISSING_ACTION.");
      }
      if ((!item.path("object").isTextual() || item.path("object").asText().isBlank())
          && !summary.contains("MISSING_OBJECT")
          && !summary.contains("UNRESOLVED_REFERENCE")) {
        fail("A task without an object must carry an object or reference ambiguity.");
      }
    }
  }

  private Set<String> reasonValues(JsonNode reasons) {
    Set<String> values = new HashSet<>();
    for (JsonNode reason : reasons) {
      values.add(reason.asText());
    }
    return Set.copyOf(values);
  }

  private String topType(JsonNode candidates) {
    String value = null;
    double score = -1;
    for (JsonNode candidate : candidates) {
      double candidateScore = candidate.path("score").asDouble();
      if (candidateScore > score) {
        score = candidateScore;
        value = candidate.path("value").asText();
      }
    }
    return value;
  }

  private void validateSuggestedTitle(JsonNode title) {
    requireObject(title, "suggestedTitle");
    rejectUnknownFields(title, "suggestedTitle", SCORED_TITLE_FIELDS);
    requireText(title, "value", 1, 200);
    requireScore(title.path("confidence"), "suggestedTitle.confidence");
    if (!title.path("needsConfirmation").isBoolean()) {
      fail("suggestedTitle.needsConfirmation must be a boolean.");
    }
  }

  private void validateTypeCandidates(JsonNode candidates) {
    requireArray(candidates, "typeCandidates", 1, 5);
    Set<String> values = new HashSet<>();
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "typeCandidates[]");
      rejectUnknownFields(candidate, "typeCandidates[]", TYPE_CANDIDATE_FIELDS);
      String value = requireText(candidate, "value", 1, 32);
      if (!TYPE_CANDIDATES.contains(value)) {
        fail("typeCandidates[] contains an unsupported value.");
      }
      if (!values.add(value)) {
        fail("typeCandidates[] values must be unique.");
      }
      requireScore(candidate.path("score"), "typeCandidates[].score");
    }
  }

  private ValidatedDateCandidates validateDateCandidates(
      JsonNode candidates, String schemaVersion) {
    requireArray(candidates, "dateCandidates", 0, 5);
    Set<String> candidateIds = new HashSet<>();
    Set<String> preciseCandidateIds = new HashSet<>();
    Map<String, ValidatedDateCandidate> candidatesById = new HashMap<>();
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "dateCandidates[]");
      rejectUnknownFields(candidate, "dateCandidates[]", DATE_CANDIDATE_FIELDS);
      String candidateId = null;
      if (!"1".equals(schemaVersion)) {
        candidateId = requireText(candidate, "candidateId", 1, 100);
        if (!candidateIds.add(candidateId)) {
          fail("dateCandidates[].candidateId must be unique.");
        }
      } else if (candidate.has("candidateId")) {
        fail("dateCandidates[].candidateId is only supported by schema version 2 or 3.");
      }
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
      validateCandidateDateValue(
          candidate.path("value"), precision, candidate.path("timeSpecified").asBoolean());
      if (candidateId != null
          && Set.of("DATE_ONLY", "EXACT_TIME", "RELATIVE_EXACT").contains(precision)) {
        preciseCandidateIds.add(candidateId);
      }
      if (candidateId != null) {
        candidatesById.put(
            candidateId,
            new ValidatedDateCandidate(
                precision,
                candidate.path("value").isTextual() ? candidate.path("value").asText() : null));
      }
    }
    return new ValidatedDateCandidates(
        Set.copyOf(candidateIds), Set.copyOf(preciseCandidateIds), Map.copyOf(candidatesById));
  }

  private void validateCandidateDateValue(JsonNode value, String precision, boolean timeSpecified) {
    switch (precision) {
      case "DATE_ONLY" -> validateDateOnlyCandidate(value, timeSpecified);
      case "EXACT_TIME", "RELATIVE_EXACT" -> validateExactTimeCandidate(value, timeSpecified);
      case "APPROXIMATE", "UNKNOWN" -> validateImpreciseCandidate(value, timeSpecified);
      default -> throw new IllegalStateException("Unexpected date precision.");
    }
  }

  private void validateDateOnlyCandidate(JsonNode value, boolean timeSpecified) {
    if (!value.isTextual() || timeSpecified) {
      fail("DATE_ONLY requires a calendar date without an explicit time.");
    }
    try {
      LocalDate.parse(value.asText());
    } catch (DateTimeParseException exception) {
      fail("DATE_ONLY requires a valid ISO-8601 calendar date.");
    }
  }

  private void validateExactTimeCandidate(JsonNode value, boolean timeSpecified) {
    if (!value.isTextual() || !timeSpecified) {
      fail("An exact date candidate requires a timestamp with an explicit time.");
    }
    try {
      OffsetDateTime.parse(value.asText());
    } catch (DateTimeParseException exception) {
      fail("An exact date candidate requires a valid ISO-8601 timestamp with an offset.");
    }
  }

  private void validateImpreciseCandidate(JsonNode value, boolean timeSpecified) {
    if (!value.isNull() || timeSpecified) {
      fail("An approximate or unknown date candidate cannot contain an exact value or time.");
    }
  }

  private void validateTagCandidates(JsonNode candidates) {
    requireArray(candidates, "tagCandidates", 0, 10);
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "tagCandidates[]");
      rejectUnknownFields(candidate, "tagCandidates[]", TAG_CANDIDATE_FIELDS);
      JsonNode existingTagId = candidate.path("existingTagId");
      if (!existingTagId.isNull() && !existingTagId.isTextual()) {
        fail("tagCandidates[].existingTagId must be a UUID or null.");
      }
      if (existingTagId.isTextual()) {
        parseUuid(existingTagId.asText(), "tagCandidates[].existingTagId");
      }
      requireText(candidate, "canonicalName", 1, 100);
      JsonNode matchedAlias = candidate.path("matchedAlias");
      requireNullableText(matchedAlias, "tagCandidates[].matchedAlias", 100);
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

  private Set<String> validateItemCandidates(
      JsonNode candidates,
      int contentLength,
      String schemaVersion,
      ValidatedDateCandidates dates,
      Set<String> proposalAmbiguityReasons) {
    requireArray(candidates, "itemCandidates", 0, 3);
    Set<String> candidateIds = new HashSet<>();
    Set<String> eventScheduleCandidateIds = new HashSet<>();
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "itemCandidates[]");
      rejectUnknownFields(candidate, "itemCandidates[]", ITEM_CANDIDATE_FIELDS);
      String candidateId = requireText(candidate, "candidateId", 1, 100);
      if (!candidateIds.add(candidateId)) {
        fail("itemCandidates[].candidateId must be unique.");
      }
      String kind = requireText(candidate, "kind", 1, 32);
      if (!ITEM_KINDS.contains(kind)) {
        fail("itemCandidates[] contains an unsupported kind.");
      }
      requireText(candidate, "title", 1, 200);
      validateDueDateCandidateReference(candidate, kind, schemaVersion, dates);
      validateEventScheduleCandidates(
          candidate,
          kind,
          schemaVersion,
          dates,
          eventScheduleCandidateIds,
          proposalAmbiguityReasons);
      validateSourceSpan(candidate.path("sourceSpan"), contentLength);
      requireNullableText(candidate.path("action"), "itemCandidates[].action", 200);
      requireNullableText(candidate.path("object"), "itemCandidates[].object", 200);
      requireScore(candidate.path("confidence"), "itemCandidates[].confidence");
    }
    return candidateIds;
  }

  private void validateDueDateCandidateReference(
      JsonNode candidate, String kind, String schemaVersion, ValidatedDateCandidates dates) {
    if ("1".equals(schemaVersion)) {
      if (candidate.has("dueDateCandidateId")) {
        fail("itemCandidates[].dueDateCandidateId is only supported by schema version 2 or 3.");
      }
      return;
    }

    if (!candidate.has("dueDateCandidateId")) {
      fail("itemCandidates[].dueDateCandidateId is required by schema version 2 or 3.");
    }
    JsonNode reference = candidate.path("dueDateCandidateId");
    if (reference.isNull()) {
      return;
    }
    if (!reference.isTextual() || reference.asText().isBlank()) {
      fail("itemCandidates[].dueDateCandidateId must be text or null.");
    }
    if (!"TASK".equals(kind)) {
      fail("Only a TASK item may reference a due date candidate.");
    }
    if (!dates.candidateIds().contains(reference.asText())) {
      fail("itemCandidates[].dueDateCandidateId references an unknown date candidate.");
    }
    if (!dates.preciseCandidateIds().contains(reference.asText())) {
      fail("An approximate or unknown date candidate cannot be proposed as a task due date.");
    }
  }

  private void validateEventScheduleCandidates(
      JsonNode item,
      String kind,
      String schemaVersion,
      ValidatedDateCandidates dates,
      Set<String> globalCandidateIds,
      Set<String> proposalAmbiguityReasons) {
    if (!"3".equals(schemaVersion)) {
      if (item.has("eventScheduleCandidates") || item.has("suggestedEventScheduleCandidateId")) {
        fail("EVENT schedule proposal fields are only supported by schema version 3.");
      }
      return;
    }
    if (!item.has("eventScheduleCandidates") || !item.has("suggestedEventScheduleCandidateId")) {
      fail("Schema version 3 requires EVENT schedule proposal fields on every item.");
    }

    JsonNode scheduleCandidates = item.path("eventScheduleCandidates");
    requireArray(scheduleCandidates, "itemCandidates[].eventScheduleCandidates", 0, 5);
    JsonNode suggested = item.path("suggestedEventScheduleCandidateId");
    if (!suggested.isNull()) {
      fail("A suggested EVENT schedule is disabled until the temporal binding policy is approved.");
    }
    if (!"EVENT".equals(kind) && !scheduleCandidates.isEmpty()) {
      fail("Only an EVENT item may contain EVENT schedule candidates.");
    }
    if (scheduleCandidates.size() > 1 && !proposalAmbiguityReasons.contains("CONFLICTING_DATES")) {
      fail("Multiple EVENT schedule candidates must carry CONFLICTING_DATES.");
    }

    Set<EventScheduleSemanticKey> semanticCandidates = new HashSet<>();
    for (JsonNode scheduleCandidate : scheduleCandidates) {
      EventScheduleSemanticKey semantic =
          validateEventScheduleCandidate(scheduleCandidate, dates, globalCandidateIds);
      if (!semanticCandidates.add(semantic)) {
        fail("EVENT schedule candidates must not contain duplicate semantic alternatives.");
      }
    }
  }

  private EventScheduleSemanticKey validateEventScheduleCandidate(
      JsonNode candidate, ValidatedDateCandidates dates, Set<String> globalCandidateIds) {
    requireObject(candidate, "itemCandidates[].eventScheduleCandidates[]");
    rejectUnknownFields(
        candidate, "itemCandidates[].eventScheduleCandidates[]", EVENT_SCHEDULE_CANDIDATE_FIELDS);
    String candidateId = requireText(candidate, "candidateId", 1, 100);
    if (!globalCandidateIds.add(candidateId)) {
      fail("EVENT schedule candidate IDs must be unique within a proposal.");
    }
    String mode = requireText(candidate, "mode", 1, 16);
    if (!EVENT_SCHEDULE_MODES.contains(mode)) {
      fail("EVENT schedule candidates contain an unsupported mode.");
    }
    String startDateCandidateId = requireText(candidate, "startDateCandidateId", 1, 100);
    ValidatedDateCandidate start = dates.candidatesById().get(startDateCandidateId);
    if (start == null) {
      fail("An EVENT schedule start references an unknown date candidate.");
    }
    requireScore(candidate.path("score"), "itemCandidates[].eventScheduleCandidates[].score");

    JsonNode endNode = candidate.path("end");
    ValidatedEventScheduleEnd end = validateEventScheduleEnd(endNode, mode, dates);
    return switch (mode) {
      case "TIMED" -> validateTimedEventScheduleCandidate(start, end);
      case "ALL_DAY" -> validateAllDayEventScheduleCandidate(start, end);
      default -> throw new IllegalStateException("Unexpected EVENT schedule mode.");
    };
  }

  private ValidatedEventScheduleEnd validateEventScheduleEnd(
      JsonNode endNode, String mode, ValidatedDateCandidates dates) {
    if (endNode.isNull()) {
      return null;
    }
    requireObject(endNode, "itemCandidates[].eventScheduleCandidates[].end");
    rejectUnknownFields(
        endNode, "itemCandidates[].eventScheduleCandidates[].end", EVENT_SCHEDULE_END_FIELDS);
    String dateCandidateId = requireText(endNode, "dateCandidateId", 1, 100);
    ValidatedDateCandidate dateCandidate = dates.candidatesById().get(dateCandidateId);
    if (dateCandidate == null) {
      fail("An EVENT schedule end references an unknown date candidate.");
    }
    String boundary = requireText(endNode, "boundary", 1, 32);
    if (!EVENT_SCHEDULE_END_BOUNDARIES.contains(boundary)) {
      fail("An EVENT schedule end contains an unsupported boundary.");
    }
    if ("TIMED".equals(mode) && !"EXCLUSIVE_AT_VALUE".equals(boundary)) {
      fail("A timed EVENT end must use EXCLUSIVE_AT_VALUE.");
    }
    return new ValidatedEventScheduleEnd(dateCandidate, boundary);
  }

  private EventScheduleSemanticKey validateTimedEventScheduleCandidate(
      ValidatedDateCandidate start, ValidatedEventScheduleEnd end) {
    if (!Set.of("EXACT_TIME", "RELATIVE_EXACT").contains(start.precision())) {
      fail("A timed EVENT start must reference an exact timestamp candidate.");
    }
    OffsetDateTime startValue = OffsetDateTime.parse(start.value());
    OffsetDateTime endValue = null;
    if (end != null) {
      if (!Set.of("EXACT_TIME", "RELATIVE_EXACT").contains(end.date().precision())) {
        fail("A timed EVENT end must reference an exact timestamp candidate.");
      }
      endValue = OffsetDateTime.parse(end.date().value());
      if (!endValue.toInstant().isAfter(startValue.toInstant())) {
        fail("A timed EVENT end must be after its start.");
      }
    }
    return new EventScheduleSemanticKey(
        "TIMED",
        startValue.toInstant().toString(),
        endValue == null ? null : endValue.toInstant().toString());
  }

  private EventScheduleSemanticKey validateAllDayEventScheduleCandidate(
      ValidatedDateCandidate start, ValidatedEventScheduleEnd end) {
    if (!"DATE_ONLY".equals(start.precision())) {
      fail("An all-day EVENT start must reference a date-only candidate.");
    }
    LocalDate startValue = LocalDate.parse(start.value());
    LocalDate exclusiveEnd = null;
    if (end != null) {
      if (!"DATE_ONLY".equals(end.date().precision())) {
        fail("An all-day EVENT end must reference a date-only candidate.");
      }
      LocalDate endValue = LocalDate.parse(end.date().value());
      try {
        exclusiveEnd =
            "INCLUSIVE_THROUGH_VALUE".equals(end.boundary()) ? endValue.plusDays(1) : endValue;
      } catch (DateTimeException exception) {
        fail("An inclusive all-day EVENT end is outside the supported date range.");
      }
      if (exclusiveEnd.getYear() < 0 || exclusiveEnd.getYear() > 9999) {
        fail("An inclusive all-day EVENT end is outside the supported date range.");
      }
      if (!exclusiveEnd.isAfter(startValue)) {
        fail("An all-day EVENT exclusive end must be after its start.");
      }
    }
    return new EventScheduleSemanticKey(
        "ALL_DAY", startValue.toString(), exclusiveEnd == null ? null : exclusiveEnd.toString());
  }

  private void validateSourceSpan(JsonNode sourceSpan, int contentLength) {
    if (sourceSpan.isNull()) {
      return;
    }
    requireObject(sourceSpan, "itemCandidates[].sourceSpan");
    rejectUnknownFields(sourceSpan, "itemCandidates[].sourceSpan", SOURCE_SPAN_FIELDS);
    JsonNode start = sourceSpan.path("start");
    JsonNode end = sourceSpan.path("end");
    if (!start.isIntegralNumber()
        || !start.canConvertToInt()
        || !end.isIntegralNumber()
        || !end.canConvertToInt()) {
      fail("itemCandidates[].sourceSpan must contain integer offsets.");
    }
    if (start.asInt() < 0 || end.asInt() <= start.asInt() || end.asInt() > contentLength) {
      fail("itemCandidates[].sourceSpan is outside the memo content.");
    }
  }

  private void validateRelationCandidates(JsonNode candidates, Set<String> itemCandidateIds) {
    requireArray(candidates, "relationCandidates", 0, 10);
    Set<RelationIdentity> uniqueRelations = new HashSet<>();
    for (JsonNode candidate : candidates) {
      requireObject(candidate, "relationCandidates[]");
      rejectUnknownFields(candidate, "relationCandidates[]", RELATION_CANDIDATE_FIELDS);
      String sourceCandidateId = requireText(candidate, "sourceCandidateId", 1, 100);
      if (!itemCandidateIds.contains(sourceCandidateId)) {
        fail("relationCandidates[] references an unknown source candidate.");
      }
      String targetType = requireText(candidate, "targetType", 1, 16);
      if (!Set.of("MEMO", "TAG").contains(targetType)) {
        fail("relationCandidates[] contains an unsupported targetType.");
      }
      UUID targetId =
          parseUuid(requireText(candidate, "targetId", 1, 64), "relationCandidates[].targetId");
      String relationType = requireText(candidate, "relationType", 1, 32);
      if (!Set.of("RELATED_TO", "CONTINUES", "DEPENDS_ON", "REFERENCES").contains(relationType)) {
        fail("relationCandidates[] contains an unsupported relationType.");
      }
      if (!uniqueRelations.add(
          new RelationIdentity(sourceCandidateId, targetType, targetId, relationType))) {
        fail("relationCandidates[] must not contain duplicate directed relations.");
      }
      requireScore(candidate.path("score"), "relationCandidates[].score");
    }
  }

  private record RelationIdentity(
      String sourceCandidateId, String targetType, UUID targetId, String relationType) {}

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
    int length = text.codePointCount(0, text.length());
    if (text.isBlank() || length < minimum || length > maximum) {
      fail(field + " has an invalid length.");
    }
    return text;
  }

  private void requireNullableText(JsonNode value, String field, int maximum) {
    if (!value.isNull() && !value.isTextual()) {
      fail(field + " must be text or null.");
    }
    if (value.isTextual()) {
      String text = value.asText();
      int length = text.codePointCount(0, text.length());
      if (text.isBlank() || length > maximum) {
        fail(field + " has an invalid length.");
      }
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

  private void rejectUnknownFields(JsonNode value, String field, Set<String> allowedFields) {
    for (var property : value.properties()) {
      if (!allowedFields.contains(property.getKey())) {
        fail(field + " contains an unsupported property.");
      }
    }
  }

  private UUID parseUuid(String value, String field) {
    try {
      UUID uuid = UUID.fromString(value);
      if (!uuid.toString().equalsIgnoreCase(value)) {
        fail(field + " must be a canonical UUID.");
      }
      return uuid;
    } catch (IllegalArgumentException exception) {
      fail(field + " must be a UUID.");
      throw new IllegalStateException("Unreachable");
    }
  }

  private void fail(String reason) {
    throw DomainException.invalid("INVALID_ANALYSIS_PROPOSAL", reason);
  }

  private record ValidatedDateCandidate(String precision, String value) {}

  private record ValidatedDateCandidates(
      Set<String> candidateIds,
      Set<String> preciseCandidateIds,
      Map<String, ValidatedDateCandidate> candidatesById) {}

  private record ValidatedEventScheduleEnd(ValidatedDateCandidate date, String boundary) {}

  private record EventScheduleSemanticKey(String mode, String start, String exclusiveEnd) {}
}
