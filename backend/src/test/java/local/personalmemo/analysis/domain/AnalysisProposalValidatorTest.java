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
