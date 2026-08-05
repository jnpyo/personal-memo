package local.personalmemo.memo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class MemoLifecycleIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void listIsOwnerScopedStatusFilteredAndBounded() throws Exception {
    UUID activeMemoId = UUID.randomUUID();
    UUID trashedMemoId = UUID.randomUUID();
    UUID anotherActiveMemoId = UUID.randomUUID();
    UUID foreignMemoId = seedForeignMemo();
    createMemo(activeMemoId, "create-active-for-list", "active memo");
    createMemo(trashedMemoId, "create-trashed-for-list", "trashed memo");
    createMemo(anotherActiveMemoId, "create-another-active-for-list", "another active memo");
    trashMemo(trashedMemoId, "trash-for-list");

    var active = mvc.perform(get("/api/v1/memos").param("status", "ACTIVE")).andReturn();
    var trashed = mvc.perform(get("/api/v1/memos").param("status", "TRASHED")).andReturn();
    var bounded =
        mvc.perform(get("/api/v1/memos").param("status", "ACTIVE").param("limit", "1"))
            .andReturn();
    var invalid =
        mvc.perform(get("/api/v1/memos").param("status", "DELETED")).andReturn();

    assertThat(active.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(active).size()).isEqualTo(2);
    assertThat(response(active).toString()).contains(activeMemoId.toString());
    assertThat(response(active).toString()).contains(anotherActiveMemoId.toString());
    assertThat(response(active).toString()).doesNotContain(trashedMemoId.toString());
    assertThat(response(active).toString()).doesNotContain(foreignMemoId.toString());

    assertThat(trashed.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(trashed).size()).isEqualTo(1);
    assertThat(response(trashed).at("/0/id").asText()).isEqualTo(trashedMemoId.toString());
    assertThat(response(trashed).at("/0/status").asText()).isEqualTo("TRASHED");

    assertThat(bounded.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(bounded).size()).isEqualTo(1);
    assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(invalid).path("code").asText()).isEqualTo("INVALID_MEMO_STATUS");
  }

  @Test
  void updateIsIdempotentAndKeepsEveryRawRevision() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-before-idempotent-update", "raw revision one");

    var first = updateMemo(memoId, "same-update-key", 1, "raw revision two");
    var replay = updateMemo(memoId, "same-update-key", 1, "raw revision two");
    var mismatch = updateMemo(memoId, "same-update-key", 1, "different payload");

    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertThat(replay.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(replay)).isEqualTo(response(first));
    assertThat(response(first).path("currentRevision").asInt()).isEqualTo(2);
    assertThat(mismatch.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(mismatch).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:id order by revision")
                .param("id", memoId)
                .query(String.class)
                .list())
        .containsExactly("raw revision one", "raw revision two");
  }

  @Test
  void trashAndRestoreAreIdempotentAndRestoredMemoCanBeAnalyzedAgain() throws Exception {
    UUID memoId = UUID.randomUUID();
    UUID anotherMemoId = UUID.randomUUID();
    createMemo(memoId, "create-before-trash", "11.25 OS assignment submit");
    createMemo(anotherMemoId, "create-another-before-trash", "another memo");
    var started = startAnalysis(memoId, "start-before-trash", 1);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    mvc.perform(
            post("/api/v1/analysis-proposals/{id}/postpone", proposalId)
                .header("Idempotency-Key", "postpone-before-trash"))
        .andReturn();

    var firstTrash = trashMemo(memoId, "same-trash-key");
    var trashReplay = trashMemo(memoId, "same-trash-key");
    var trashMismatch = trashMemo(anotherMemoId, "same-trash-key");

    assertThat(firstTrash.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(firstTrash).path("status").asText()).isEqualTo("TRASHED");
    assertThat(response(trashReplay)).isEqualTo(response(firstTrash));
    assertThat(trashMismatch.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(trashMismatch).path("code").asText())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(
            db.sql("select status from analysis_runs where id=:id")
                .param("id", runId)
                .query(String.class)
                .single())
        .isEqualTo("STALE");

    var firstRestore = restoreMemo(memoId, "same-restore-key");
    var restoreReplay = restoreMemo(memoId, "same-restore-key");
    var restoreMismatch = restoreMemo(anotherMemoId, "same-restore-key");

    assertThat(firstRestore.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(firstRestore).path("status").asText()).isEqualTo("ACTIVE");
    assertThat(response(restoreReplay)).isEqualTo(response(firstRestore));
    assertThat(restoreMismatch.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(restoreMismatch).path("code").asText())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:id order by revision")
                .param("id", memoId)
                .query(String.class)
                .list())
        .containsExactly("11.25 OS assignment submit");

    var restarted = startAnalysis(memoId, "start-after-restore", 1);
    assertThat(restarted.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(restarted).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
  }

  @Test
  void trashHidesDerivedTaskAndGraphAndRestoreMakesThemVisibleAgain() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-before-derived-visibility", "11.25 OS assignment submit");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "start-before-derived-visibility", 1))
                .path("proposalId")
                .asText());
    var applied =
        applyProposal(
            proposalId,
            "apply-before-derived-visibility",
            1,
            "OS assignment submit",
            null);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    UUID taskId = db.sql("select memo_item_id from task_details").query(UUID.class).single();

    assertDerivedVisibility(memoId, taskId, true);
    trashMemo(memoId, "trash-derived-visibility");
    assertDerivedVisibility(memoId, taskId, false);

    var rejectedTaskUpdate =
        mvc.perform(
                patch("/api/v1/tasks/{id}", taskId)
                    .header("Idempotency-Key", "update-task-while-trashed")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("status", "DONE"))))
            .andReturn();
    assertThat(rejectedTaskUpdate.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(rejectedTaskUpdate).path("code").asText()).isEqualTo("MEMO_NOT_ACTIVE");
    assertThat(
            db.sql("select status from task_details where memo_item_id=:id")
                .param("id", taskId)
                .query(String.class)
                .single())
        .isEqualTo("TODO");

    restoreMemo(memoId, "restore-derived-visibility");
    assertDerivedVisibility(memoId, taskId, true);
  }

  private void assertDerivedVisibility(UUID memoId, UUID taskId, boolean visible)
      throws Exception {
    var tasks = mvc.perform(get("/api/v1/tasks")).andReturn();
    var graph = mvc.perform(get("/api/v1/graph/home")).andReturn();
    assertThat(tasks.getResponse().getStatus()).isEqualTo(200);
    assertThat(graph.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(tasks).toString().contains(taskId.toString())).isEqualTo(visible);
    assertThat(response(graph).toString().contains("memo:" + memoId)).isEqualTo(visible);
  }

  private UUID seedForeignMemo() {
    UUID ownerId = UUID.randomUUID();
    UUID memoId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.parse("2026-08-05T02:00:00Z"));
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", ownerId)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into memos(
              id, owner_id, current_revision, status, pinned, created_at, updated_at
            ) values (
              :memoId, :ownerId, 1, 'ACTIVE', false, :now, :now
            )
            """)
        .param("memoId", memoId)
        .param("ownerId", ownerId)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into memo_revisions(
              memo_id, owner_id, revision, content, content_hash, created_at, created_by
            ) values (
              :memoId, :ownerId, 1, 'foreign memo', repeat('f', 64), :now, :ownerId
            )
            """)
        .param("memoId", memoId)
        .param("ownerId", ownerId)
        .param("now", now)
        .update();
    return memoId;
  }
}
