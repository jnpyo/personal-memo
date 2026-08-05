package local.personalmemo.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class PrimaryFlowApiIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void captureReviewApplyGraphAndUndoPreservesRawMemo() throws Exception {
    UUID memoId = UUID.randomUUID();
    String rawMemo = "11.25 OS과제 제출";

    var created = createMemo(memoId, "memo-create-primary", rawMemo);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertThat(response(created).path("content").asText()).isEqualTo(rawMemo);
    assertThat(response(created).path("currentRevision").asInt()).isEqualTo(1);

    var started = startAnalysis(memoId, "analysis-start-primary", 1);
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());

    var proposal =
        mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(response(proposal).path("memoId").asText()).isEqualTo(memoId.toString());
    assertThat(response(proposal).path("memoRevision").asInt()).isEqualTo(1);
    assertThat(response(proposal).at("/typeCandidates/0/value").asText()).isEqualTo("TASK");
    assertThat(response(proposal).at("/dateCandidates/0/surfaceText").asText())
        .isEqualTo("11.25");
    assertThat(response(proposal).at("/tagCandidates/0/canonicalName").asText())
        .isEqualTo("운영체제");
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();

    Map<String, Object> due =
        Map.of(
            "surfaceText", "11.25",
            "value", "2026-11-25",
            "precision", "DATE_ONLY",
            "timeZone", "Asia/Seoul",
            "timeSpecified", false);
    var applied =
        applyProposal(proposalId, "analysis-apply-primary", 1, "OS과제 제출", due);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    UUID applicationId = UUID.fromString(response(applied).path("applicationId").asText());
    assertThat(response(applied).path("status").asText()).isEqualTo("APPLIED");

    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select due_local_date from task_details").query(LocalDate.class).single())
        .isEqualTo(LocalDate.of(2026, 11, 25));
    assertThat(
            db.sql("select due_at_utc is null from task_details").query(Boolean.class).single())
        .isTrue();

    var tasks = mvc.perform(get("/api/v1/tasks")).andExpect(status().isOk()).andReturn();
    assertThat(response(tasks).size()).isEqualTo(1);
    assertThat(response(tasks).at("/0/status").asText()).isEqualTo("TODO");

    var graph =
        mvc.perform(get("/api/v1/graph/home").param("limit", "100"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(response(graph).path("nodes").toString()).contains("memo:" + memoId);
    assertThat(response(graph).path("nodes").toString()).contains("tag:" + OPERATING_SYSTEMS_TAG_ID);
    assertThat(response(graph).path("nodes"))
        .allSatisfy(node -> assertThat(node.path("kind").asText()).isIn("MEMO", "TAG"));

    var undone = undoApplication(applicationId, "analysis-undo-primary");
    assertThat(undone.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(undone).path("status").asText()).isEqualTo("UNDONE");
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();

    var rawAfterUndo =
        mvc.perform(get("/api/v1/memos/{id}", memoId))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(response(rawAfterUndo).path("content").asText()).isEqualTo(rawMemo);
    assertThat(response(rawAfterUndo).path("currentRevision").asInt()).isEqualTo(1);
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:id and revision=1")
                .param("id", memoId)
                .query(String.class)
                .single())
        .isEqualTo(rawMemo);
  }
}
