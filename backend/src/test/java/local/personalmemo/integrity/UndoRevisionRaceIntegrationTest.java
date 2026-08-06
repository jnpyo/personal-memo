package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.personalmemo.memo.application.MemoService;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@PostgresIntegration
class UndoRevisionRaceIntegrationTest extends PostgresIntegrationTestSupport {

  @MockitoSpyBean MemoService memos;

  @Test
  void undoStartedBeforeMemoEditCannotRestoreTheOldAnalysisRunAfterTheEditCommits()
      throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "undo-race-create", "11.25 OS과제 제출");
    var analysis = startAnalysis(memoId, "undo-race-analysis", 1);
    UUID runId = UUID.fromString(response(analysis).path("id").asText());
    UUID proposalId = UUID.fromString(response(analysis).path("proposalId").asText());
    var applied = applyProposal(proposalId, "undo-race-apply", 1, "OS과제 제출", null);
    UUID applicationId = UUID.fromString(response(applied).path("applicationId").asText());

    CountDownLatch undoReachedMemoBoundary = new CountDownLatch(1);
    CountDownLatch allowUndoToLockMemo = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              undoReachedMemoBoundary.countDown();
              if (!allowUndoToLockMemo.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out before the competing memo edit completed.");
              }
              return invocation.callRealMethod();
            })
        .when(memos)
        .getCurrentForUpdate(memoId);

    try (var executor = Executors.newSingleThreadExecutor()) {
      var undo = executor.submit(() -> undoApplication(applicationId, "undo-race-undo"));
      assertThat(undoReachedMemoBoundary.await(10, TimeUnit.SECONDS)).isTrue();

      var updated = updateMemo(memoId, "undo-race-update", 1, "11.26 OS과제 수정 제출");
      assertThat(updated.getResponse().getStatus()).isEqualTo(200);
      allowUndoToLockMemo.countDown();

      assertThat(undo.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
    } finally {
      allowUndoToLockMemo.countDown();
    }

    assertThat(
            db.sql("select status from analysis_runs where id=:runId")
                .param("runId", runId)
                .query(String.class)
                .single())
        .isEqualTo("STALE");
    assertThat(
            db.sql("select status from analysis_applications where id=:applicationId")
                .param("applicationId", applicationId)
                .query(String.class)
                .single())
        .isEqualTo("UNDONE");
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(
            db.sql("select current_revision from memos where id=:memoId")
                .param("memoId", memoId)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }
}
