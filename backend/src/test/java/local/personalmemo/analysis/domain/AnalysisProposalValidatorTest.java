package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;
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
  void rejectsImpossibleDateAndOutOfBoundsSourceSpan() {
    UUID memoId = UUID.randomUUID();
    String content = "11.25 OS과제 제출";
    ObjectNode impossibleDate = proposal(memoId, 1, content);
    ((ObjectNode) impossibleDate.at("/dateCandidates/0")).put("value", "2026-02-30");
    ObjectNode invalidSpan = proposal(memoId, 1, content);
    ((ObjectNode) invalidSpan.at("/itemCandidates/0"))
        .set(
            "sourceSpan",
            json.createObjectNode().put("start", 0).put("end", content.length() + 1));

    assertInvalidProposal(() -> validator.validate(impossibleDate, memoId, 1, content.length()));
    assertInvalidProposal(() -> validator.validate(invalidSpan, memoId, 1, content.length()));
  }

  private ObjectNode proposal(UUID memoId, int revision, String content) {
    return analyzer.analyze(
        memoId,
        revision,
        content,
        Instant.parse("2026-08-05T02:00:00Z"),
        "Asia/Seoul");
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
