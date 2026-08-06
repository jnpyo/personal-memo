package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class IdempotencyIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void concurrentAnalysisStartWithOneKeyProducesOneRunAndOneProposal() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-before-concurrent-start", "11.25 OS과제 제출");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                go.await();
                return startAnalysis(memoId, "concurrent-start-key", 1);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                go.await();
                return startAnalysis(memoId, "concurrent-start-key", 1);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();

      var firstResult = first.get(15, TimeUnit.SECONDS);
      var secondResult = second.get(15, TimeUnit.SECONDS);
      assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
      assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(firstResult).path("id").asText())
          .isEqualTo(response(secondResult).path("id").asText());
      assertThat(response(firstResult).path("proposalId").asText())
          .isEqualTo(response(secondResult).path("proposalId").asText());
    }

    assertThat(db.sql("select count(*) from analysis_runs").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from analysis_proposals").query(Long.class).single())
        .isEqualTo(1);
    assertThat(
            db.sql(
                    "select count(*) from idempotency_records "
                        + "where operation='ANALYSIS_START' and idempotency_key='concurrent-start-key'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void duplicateMemoCreateReturnsOriginalAndRejectsPayloadMismatch() throws Exception {
    UUID memoId = UUID.randomUUID();
    var first = createMemo(memoId, "same-create-key", "원본 메모");
    var duplicate = createMemo(memoId, "same-create-key", "원본 메모");

    assertThat(first.getResponse().getStatus()).isEqualTo(201);
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(201);
    assertThat(response(duplicate).path("id").asText()).isEqualTo(memoId.toString());
    assertThat(db.sql("select count(*) from memos").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from memo_revisions").query(Long.class).single())
        .isEqualTo(1);

    var mismatch = createMemo(memoId, "same-create-key", "다른 요청 본문");
    assertIdempotencyConflict(mismatch);
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:id")
                .param("id", memoId)
                .query(String.class)
                .single())
        .isEqualTo("원본 메모");
  }

  @Test
  void duplicateStartAndApplyCreateExactlyOneResourceSet() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-for-idempotency", "11.25 OS과제 제출");

    var firstStart = startAnalysis(memoId, "same-start-key", 1);
    var duplicateStart = startAnalysis(memoId, "same-start-key", 1);
    UUID proposalId = UUID.fromString(response(firstStart).path("proposalId").asText());

    assertThat(response(duplicateStart).path("id").asText())
        .isEqualTo(response(firstStart).path("id").asText());
    assertThat(response(duplicateStart).path("proposalId").asText())
        .isEqualTo(proposalId.toString());
    assertThat(db.sql("select count(*) from analysis_runs").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from analysis_proposals").query(Long.class).single())
        .isEqualTo(1);

    UUID anotherMemoId = UUID.randomUUID();
    createMemo(anotherMemoId, "create-another-for-start-conflict", "다른 메모");
    var changedStartBody = Map.of("memoRevision", 1, "policy", "AUTO");
    var changedStart =
        mvc.perform(
                post("/api/v1/memos/{id}/analysis-runs", anotherMemoId)
                    .header("Idempotency-Key", "same-start-key")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(changedStartBody)))
            .andReturn();
    assertIdempotencyConflict(changedStart);

    Map<String, Object> due =
        Map.of(
            "surfaceText", "11.25",
            "value", "2026-11-25",
            "precision", "DATE_ONLY",
            "timeZone", "Asia/Seoul",
            "timeSpecified", false);
    var firstApply = applyProposal(proposalId, "same-apply-key", 1, "OS과제 제출", due);
    var duplicateApply = applyProposal(proposalId, "same-apply-key", 1, "OS과제 제출", due);
    UUID applicationId = UUID.fromString(response(firstApply).path("applicationId").asText());

    assertThat(response(duplicateApply).path("applicationId").asText())
        .isEqualTo(applicationId.toString());
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from item_tags").query(Long.class).single()).isEqualTo(1);

    var changedApply = applyProposal(proposalId, "same-apply-key", 1, "동일 키의 다른 제목", due);
    assertIdempotencyConflict(changedApply);
    assertThat(
            db.sql("select title from memo_items where application_id=:id")
                .param("id", applicationId)
                .query(String.class)
                .single())
        .isEqualTo("OS과제 제출");
  }

  @Test
  void duplicateUndoReturnsOriginalResultWithoutTouchingRawMemo() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-for-undo-idempotency", "11.25 OS과제 제출");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "start-for-undo-idempotency", 1))
                .path("proposalId")
                .asText());
    UUID applicationId =
        UUID.fromString(
            response(applyProposal(proposalId, "apply-for-undo-idempotency", 1, "OS과제 제출", null))
                .path("applicationId")
                .asText());

    var firstUndo = undoApplication(applicationId, "same-undo-key");
    var duplicateUndo = undoApplication(applicationId, "same-undo-key");

    assertThat(firstUndo.getResponse().getStatus()).isEqualTo(200);
    assertThat(duplicateUndo.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(duplicateUndo).path("applicationId").asText())
        .isEqualTo(applicationId.toString());
    assertThat(response(duplicateUndo).path("status").asText()).isEqualTo("UNDONE");
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(
            db.sql("select count(*) from memo_revisions where memo_id=:id")
                .param("id", memoId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  private void assertIdempotencyConflict(org.springframework.test.web.servlet.MvcResult result)
      throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(result).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }
}
