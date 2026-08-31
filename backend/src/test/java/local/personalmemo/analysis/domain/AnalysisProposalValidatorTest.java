package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class AnalysisProposalValidatorTest {

  private final ObjectMapper json = new ObjectMapper();
  private final FakeAnalyzer analyzer = new FakeAnalyzer(json);
  private final AnalysisProposalValidator validator = new AnalysisProposalValidator();

  @Test
  void acceptsVersionedFakeProposalForTheExactMemoRevision() {
    UUID memoId = UUID.randomUUID();
    String content = "11.25 OS과제 제출";
    ObjectNode proposal = proposal(memoId, 3, content);

    assertThatCode(() -> validator.validate(proposal, memoId, 3, content.length()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsUnknownSchemaVersionAndMismatchedIdentity() {
    UUID memoId = UUID.randomUUID();
    String content = "11.25 OS과제 제출";
    ObjectNode unknownSchema = proposal(memoId, 1, content).put("schemaVersion", "999");
    ObjectNode wrongMemo = proposal(memoId, 1, content).put("memoId", UUID.randomUUID().toString());

    assertInvalidProposal(() -> validator.validate(unknownSchema, memoId, 1, content.length()));
    assertInvalidProposal(() -> validator.validate(wrongMemo, memoId, 1, content.length()));
  }

  @Test
  void rejectsImpossibleDateAndInvalidSourceSpans() {
    UUID memoId = UUID.randomUUID();
    String content = "11.25 OS과제 제출";
    ObjectNode impossibleDate = proposal(memoId, 1, content);
    ((ObjectNode) impossibleDate.at("/dateCandidates/0")).put("value", "2026-02-30");
    ObjectNode invalidSpan = proposal(memoId, 1, content);
    ((ObjectNode) invalidSpan.at("/itemCandidates/0"))
        .set(
            "sourceSpan", json.createObjectNode().put("start", 0).put("end", content.length() + 1));
    ObjectNode emptySpan = proposal(memoId, 1, content);
    ((ObjectNode) emptySpan.at("/itemCandidates/0"))
        .set("sourceSpan", json.createObjectNode().put("start", 3).put("end", 3));

    assertInvalidProposal(() -> validator.validate(impossibleDate, memoId, 1, content.length()));
    assertInvalidProposal(() -> validator.validate(invalidSpan, memoId, 1, content.length()));
    assertInvalidProposal(() -> validator.validate(emptySpan, memoId, 1, content.length()));
  }

  @Test
  void rejectsSourceSpansThatSplitUtf16SurrogatePairs() {
    UUID memoId = UUID.randomUUID();
    String content = "😀 과제 제출";
    ObjectNode splitSurrogate = proposal(memoId, 1, content);
    ((ObjectNode) splitSurrogate.at("/itemCandidates/0"))
        .set("sourceSpan", json.createObjectNode().put("start", 1).put("end", content.length()));

    assertInvalidProposal(
        () ->
            validator.validate(
                splitSurrogate, memoId, 1, content, analyzer.provenance(), "field-policy-v1"));
  }

  @Test
  void keepsLegacyVersionOneProposalsReadableWithoutBindingFields() {
    UUID memoId = UUID.randomUUID();
    String content = "11.25 OS과제 제출";
    ObjectNode proposal = proposal(memoId, 1, content).put("schemaVersion", "1");
    for (JsonNode date : proposal.path("dateCandidates")) {
      ((ObjectNode) date).remove("candidateId");
    }
    for (JsonNode item : proposal.path("itemCandidates")) {
      ((ObjectNode) item).remove("dueDateCandidateId");
    }

    assertThatCode(() -> validator.validate(proposal, memoId, 1, content.length()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsDuplicateDanglingNonTaskAndImpreciseVersionTwoBindings() {
    UUID memoId = UUID.randomUUID();
    String twoDateContent = "보고서 초안은 11월 20일, 최종 제출은 11월 25일";
    ObjectNode duplicateDateIds = proposal(memoId, 1, twoDateContent);
    ((ObjectNode) duplicateDateIds.at("/dateCandidates/1")).put("candidateId", "date-1");

    String exactContent = "11.25 OS과제 제출";
    ObjectNode dangling = proposal(memoId, 1, exactContent);
    ((ObjectNode) dangling.at("/itemCandidates/0")).put("dueDateCandidateId", "date-missing");
    ObjectNode nonTask = proposal(memoId, 1, exactContent);
    ((ObjectNode) nonTask.at("/itemCandidates/0")).put("kind", "INFORMATION");
    ((ObjectNode) nonTask.at("/typeCandidates/0")).put("value", "INFORMATION");

    String approximateContent = "주말쯤 병원 예약 잡기";
    ObjectNode imprecise = proposal(memoId, 1, approximateContent);
    ((ObjectNode) imprecise.at("/itemCandidates/0")).put("dueDateCandidateId", "date-1");

    assertInvalidProposal(
        () -> validator.validate(duplicateDateIds, memoId, 1, twoDateContent.length()));
    assertInvalidProposal(() -> validator.validate(dangling, memoId, 1, exactContent.length()));
    assertInvalidProposal(() -> validator.validate(nonTask, memoId, 1, exactContent.length()));
    assertInvalidProposal(
        () -> validator.validate(imprecise, memoId, 1, approximateContent.length()));
  }

  @Test
  void rejectsDuplicateDirectedRelationCandidates() {
    UUID memoId = UUID.randomUUID();
    String content = "11.25 OS과제 제출";
    ObjectNode duplicateRelations = proposal(memoId, 1, content);
    UUID targetId = UUID.randomUUID();
    ObjectNode relation =
        json.createObjectNode()
            .put("sourceCandidateId", "item-1")
            .put("targetType", "MEMO")
            .put("targetId", targetId.toString())
            .put("relationType", "RELATED_TO")
            .put("score", 0.9);
    ArrayNode relations = (ArrayNode) duplicateRelations.path("relationCandidates");
    relations.add(relation);
    relations.add(relation.deepCopy().put("score", 0.8));

    assertInvalidProposal(
        () -> validator.validate(duplicateRelations, memoId, 1, content.length()));
  }

  @Test
  void acceptsStructurallyBoundTimedAndInclusiveAllDayVersionThreeCandidates() {
    UUID memoId = UUID.randomUUID();
    String timedContent = "오늘 오후 6시 연구 모임";
    ObjectNode timed = versionThreeEventProposal(memoId, timedContent);
    addEventScheduleCandidate(timed, "event-schedule-1", "TIMED", "date-1", null, null);

    String allDayContent = "9월 18일 연구 모임";
    ObjectNode allDay = versionThreeEventProposal(memoId, allDayContent);
    addEventScheduleCandidate(
        allDay, "event-schedule-1", "ALL_DAY", "date-1", "date-1", "INCLUSIVE_THROUGH_VALUE");

    assertThatCode(() -> validator.validate(timed, memoId, 1, timedContent.length()))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(allDay, memoId, 1, allDayContent.length()))
        .doesNotThrowAnyException();
  }

  @Test
  void keepsVersionThreeSuggestionsDisabledAndVersionTwoStrict() {
    UUID memoId = UUID.randomUUID();
    String content = "오늘 오후 6시 연구 모임";
    ObjectNode suggested = versionThreeEventProposal(memoId, content);
    addEventScheduleCandidate(suggested, "event-schedule-1", "TIMED", "date-1", null, null);
    ((ObjectNode) suggested.at("/itemCandidates/0"))
        .put("suggestedEventScheduleCandidateId", "event-schedule-1");

    ObjectNode versionTwoWithEventFields = proposal(memoId, 1, content);
    ((ObjectNode) versionTwoWithEventFields.at("/itemCandidates/0"))
        .putArray("eventScheduleCandidates");
    ((ObjectNode) versionTwoWithEventFields.at("/itemCandidates/0"))
        .putNull("suggestedEventScheduleCandidateId");

    assertInvalidProposal(() -> validator.validate(suggested, memoId, 1, content.length()));
    assertInvalidProposal(
        () -> validator.validate(versionTwoWithEventFields, memoId, 1, content.length()));
  }

  @Test
  void rejectsDanglingMismatchedAndNonEventScheduleCandidates() {
    UUID memoId = UUID.randomUUID();
    String eventContent = "9월 18일 연구 모임";
    ObjectNode dangling = versionThreeEventProposal(memoId, eventContent);
    addEventScheduleCandidate(dangling, "event-schedule-1", "ALL_DAY", "missing", null, null);

    ObjectNode mismatched = versionThreeEventProposal(memoId, eventContent);
    addEventScheduleCandidate(mismatched, "event-schedule-1", "TIMED", "date-1", null, null);

    String taskContent = "11.25 OS과제 제출";
    ObjectNode nonEvent = versionThreeProposal(memoId, taskContent);
    addEventScheduleCandidate(nonEvent, "event-schedule-1", "ALL_DAY", "date-1", null, null);

    assertInvalidProposal(() -> validator.validate(dangling, memoId, 1, eventContent.length()));
    assertInvalidProposal(() -> validator.validate(mismatched, memoId, 1, eventContent.length()));
    assertInvalidProposal(() -> validator.validate(nonEvent, memoId, 1, taskContent.length()));
  }

  @Test
  void rejectsInvalidEndsDuplicateAlternativesAndUnsignalledMultiplicity() {
    UUID memoId = UUID.randomUUID();
    String content = "오늘 오후 6시 연구 모임";
    ObjectNode invalidTimedBoundary = versionThreeEventProposal(memoId, content);
    addEventScheduleCandidate(
        invalidTimedBoundary,
        "event-schedule-1",
        "TIMED",
        "date-1",
        "date-1",
        "INCLUSIVE_THROUGH_VALUE");

    ObjectNode invalidRange = versionThreeEventProposal(memoId, content);
    addEventScheduleCandidate(
        invalidRange, "event-schedule-1", "TIMED", "date-1", "date-1", "EXCLUSIVE_AT_VALUE");

    ObjectNode missingConflict = versionThreeEventProposal(memoId, content);
    addEventScheduleCandidate(missingConflict, "event-schedule-1", "TIMED", "date-1", null, null);
    addEventScheduleCandidate(missingConflict, "event-schedule-2", "TIMED", "date-1", null, null);

    ObjectNode duplicateSemantic = missingConflict.deepCopy();
    ((ArrayNode) duplicateSemantic.path("ambiguityReasons")).add("CONFLICTING_DATES");

    String allDayContent = "9월 18일 연구 모임";
    ObjectNode overflowingInclusiveEnd = versionThreeEventProposal(memoId, allDayContent);
    ((ObjectNode) overflowingInclusiveEnd.at("/dateCandidates/0")).put("value", "+999999999-12-31");
    addEventScheduleCandidate(
        overflowingInclusiveEnd,
        "event-schedule-1",
        "ALL_DAY",
        "date-1",
        "date-1",
        "INCLUSIVE_THROUGH_VALUE");

    ObjectNode schemaBoundaryOverflowingInclusiveEnd =
        versionThreeEventProposal(memoId, allDayContent);
    ((ObjectNode) schemaBoundaryOverflowingInclusiveEnd.at("/dateCandidates/0"))
        .put("value", "9999-12-31");
    addEventScheduleCandidate(
        schemaBoundaryOverflowingInclusiveEnd,
        "event-schedule-1",
        "ALL_DAY",
        "date-1",
        "date-1",
        "INCLUSIVE_THROUGH_VALUE");

    assertInvalidProposal(
        () -> validator.validate(invalidTimedBoundary, memoId, 1, content.length()));
    assertInvalidProposal(() -> validator.validate(invalidRange, memoId, 1, content.length()));
    assertInvalidProposal(() -> validator.validate(missingConflict, memoId, 1, content.length()));
    assertInvalidProposal(() -> validator.validate(duplicateSemantic, memoId, 1, content.length()));
    assertInvalidProposal(
        () -> validator.validate(overflowingInclusiveEnd, memoId, 1, allDayContent.length()));
    assertInvalidProposal(
        () ->
            validator.validate(
                schemaBoundaryOverflowingInclusiveEnd, memoId, 1, allDayContent.length()));
  }

  @Test
  void rejectsMissingAndUnknownVersionThreeScheduleFieldsAtDomainBoundary() {
    UUID memoId = UUID.randomUUID();
    String content = "오늘 오후 6시 연구 모임";
    ObjectNode missing = versionThreeEventProposal(memoId, content);
    ((ObjectNode) missing.at("/itemCandidates/0")).remove("eventScheduleCandidates");

    ObjectNode unknownCandidateField = versionThreeEventProposal(memoId, content);
    addEventScheduleCandidate(
        unknownCandidateField, "event-schedule-1", "TIMED", "date-1", null, null);
    ((ObjectNode) unknownCandidateField.at("/itemCandidates/0/eventScheduleCandidates/0"))
        .put("unexpected", true);

    String allDayContent = "9월 18일 연구 모임";
    ObjectNode unknownEndField = versionThreeEventProposal(memoId, allDayContent);
    addEventScheduleCandidate(
        unknownEndField, "event-schedule-1", "ALL_DAY", "date-1", "date-1", "EXCLUSIVE_AT_VALUE");
    ((ObjectNode) unknownEndField.at("/itemCandidates/0/eventScheduleCandidates/0/end"))
        .put("unexpected", true);

    assertInvalidProposal(() -> validator.validate(missing, memoId, 1, content.length()));
    assertInvalidProposal(
        () -> validator.validate(unknownCandidateField, memoId, 1, content.length()));
    assertInvalidProposal(
        () -> validator.validate(unknownEndField, memoId, 1, allDayContent.length()));
  }

  @Test
  void rejectsGloballyDuplicateEventScheduleCandidateIds() {
    UUID memoId = UUID.randomUUID();
    String content = "오늘 오후 6시 연구 모임";
    ObjectNode proposal = versionThreeEventProposal(memoId, content);
    addEventScheduleCandidate(proposal, "event-schedule-1", "TIMED", "date-1", null, null);
    ObjectNode secondItem = ((ObjectNode) proposal.at("/itemCandidates/0")).deepCopy();
    secondItem.put("candidateId", "item-2");
    ((ArrayNode) proposal.path("itemCandidates")).add(secondItem);
    ((ArrayNode) proposal.path("ambiguityReasons")).add("MULTI_INTENT");

    assertInvalidProposal(() -> validator.validate(proposal, memoId, 1, content.length()));
  }

  private ObjectNode versionThreeEventProposal(UUID memoId, String content) {
    ObjectNode proposal = versionThreeProposal(memoId, content);
    assertThat(proposal.at("/itemCandidates/0/kind").asText()).isEqualTo("EVENT");
    return proposal;
  }

  private ObjectNode versionThreeProposal(UUID memoId, String content) {
    ObjectNode proposal = proposal(memoId, 1, content).put("schemaVersion", "3");
    for (JsonNode itemNode : proposal.path("itemCandidates")) {
      ObjectNode item = (ObjectNode) itemNode;
      item.putArray("eventScheduleCandidates");
      item.putNull("suggestedEventScheduleCandidateId");
    }
    return proposal;
  }

  private void addEventScheduleCandidate(
      ObjectNode proposal,
      String candidateId,
      String mode,
      String startDateCandidateId,
      String endDateCandidateId,
      String boundary) {
    ObjectNode schedule =
        proposal
            .objectNode()
            .put("candidateId", candidateId)
            .put("mode", mode)
            .put("startDateCandidateId", startDateCandidateId)
            .put("score", 0.9);
    if (endDateCandidateId == null) {
      schedule.putNull("end");
    } else {
      schedule
          .putObject("end")
          .put("dateCandidateId", endDateCandidateId)
          .put("boundary", boundary);
    }
    ((ArrayNode) proposal.at("/itemCandidates/0/eventScheduleCandidates")).add(schedule);
  }

  private ObjectNode proposal(UUID memoId, int revision, String content) {
    return analyzer.analyze(
        memoId, revision, content, Instant.parse("2026-08-05T02:00:00Z"), "Asia/Seoul");
  }

  private void assertInvalidProposal(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo("INVALID_ANALYSIS_PROPOSAL");
              assertThat(exception.status().value()).isEqualTo(422);
            });
  }
}
