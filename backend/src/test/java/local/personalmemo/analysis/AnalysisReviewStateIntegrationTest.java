package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class AnalysisReviewStateIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void postponeKeepsProposalReviewableAndRejectMakesItTerminal() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-review-state", "11.25 OS과제 제출");
    var started = startAnalysis(memoId, "start-review-state", 1);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());

    var postponed = disposition(proposalId, "postpone", "postpone-review-key");
    var postponedReplay = disposition(proposalId, "postpone", "postpone-review-key");

    assertThat(postponed.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(postponed).path("status").asText()).isEqualTo("POSTPONED");
    assertThat(response(postponedReplay)).isEqualTo(response(postponed));
    assertThat(
            db.sql("select status from analysis_runs where id=:id")
                .param("id", runId)
                .query(String.class)
                .single())
        .isEqualTo("POSTPONED");

    var rejected = disposition(proposalId, "reject", "reject-review-key");
    var rejectedReplay = disposition(proposalId, "reject", "reject-review-key");

    assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(rejected).path("status").asText()).isEqualTo("REJECTED");
    assertThat(response(rejectedReplay)).isEqualTo(response(rejected));
    assertThat(
            db.sql("select status from analysis_runs where id=:id")
                .param("id", runId)
                .query(String.class)
                .single())
        .isEqualTo("REJECTED");

    var applyRejected =
        applyProposal(proposalId, "apply-rejected-proposal", 1, "적용 불가", null);
    assertThat(applyRejected.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(applyRejected).path("code").asText())
        .isEqualTo("PROPOSAL_NOT_APPLICABLE");
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isZero();
  }

  private org.springframework.test.web.servlet.MvcResult disposition(
      UUID proposalId, String action, String key) throws Exception {
    return mvc.perform(
            post("/api/v1/analysis-proposals/{id}/{action}", proposalId, action)
                .header("Idempotency-Key", key))
        .andReturn();
  }
}
