package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class RevisionAndRollbackIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void editingMemoIncrementsRevisionAndMakesLateProposalStale() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-before-revision-race", "11.25 OS과제 제출");
    var started = startAnalysis(memoId, "start-before-revision-race", 1);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());

    var updated = updateMemo(memoId, 1, "11.26 OS과제 수정 제출");

    assertThat(updated.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(updated).path("currentRevision").asInt()).isEqualTo(2);
    assertThat(response(updated).path("content").asText()).isEqualTo("11.26 OS과제 수정 제출");
    assertThat(
            db.sql("select status from analysis_runs where id=:id")
                .param("id", runId)
                .query(String.class)
                .single())
        .isEqualTo("STALE");
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:id order by revision")
                .param("id", memoId)
                .query(String.class)
                .list())
        .containsExactly("11.25 OS과제 제출", "11.26 OS과제 수정 제출");

    var staleApply =
        applyProposal(proposalId, "apply-stale-proposal", 1, "적용되면 안 되는 항목", null);
    assertThat(staleApply.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(staleApply).path("code").asText()).isEqualTo("STALE_MEMO_REVISION");
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isZero();
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();

    var lateStart = startAnalysis(memoId, "late-start-old-revision", 1);
    assertThat(lateStart.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(lateStart).path("code").asText()).isEqualTo("STALE_MEMO_REVISION");
  }

  @Test
  void staleOptimisticUpdateDoesNotCreateAnotherRevision() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-before-stale-update", "revision one");
    updateMemo(memoId, 1, "revision two");

    var staleUpdate = updateMemo(memoId, 1, "must not be stored");

    assertThat(staleUpdate.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(staleUpdate).path("code").asText()).isEqualTo("STALE_MEMO_REVISION");
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:id order by revision")
                .param("id", memoId)
                .query(String.class)
                .list())
        .containsExactly("revision one", "revision two");
  }

  @Test
  void invalidSecondItemRollsBackApplicationAndEveryDerivedWrite() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-before-rollback", "두 작업 후보");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "start-before-rollback", 1))
                .path("proposalId")
                .asText());

    Map<String, Object> validItem = new LinkedHashMap<>();
    validItem.put("kind", "TASK");
    validItem.put("title", "먼저 기록될 수 있는 작업");
    validItem.put("due", null);
    Map<String, Object> invalidDue =
        Map.of(
            "surfaceText", "불가능한 날짜",
            "value", "not-a-date",
            "precision", "DATE_ONLY",
            "timeZone", "Asia/Seoul",
            "timeSpecified", false);
    Map<String, Object> invalidItem =
        Map.of("kind", "TASK", "title", "날짜가 잘못된 작업", "due", invalidDue);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision", 1,
            "selectedType", "TASK",
            "title", "두 작업 후보",
            "selectedTags", List.of(),
            "items", List.of(validItem, invalidItem));

    var failed = applyProposal(proposalId, "apply-that-must-roll-back", selection);

    assertThat(failed.getResponse().getStatus()).isBetween(400, 499);
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isZero();
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from item_tags").query(Long.class).single()).isZero();
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:id")
                .param("id", memoId)
                .query(String.class)
                .single())
        .isEqualTo("두 작업 후보");
  }
}
