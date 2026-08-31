package local.personalmemo.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class GraphProjectionIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void uniqueNodesShareTagBudgetAndProjectionOrderingIsDeterministic() throws Exception {
    UUID firstMemo = createAppliedMemo("graph-first");
    UUID secondMemo = createAppliedMemo("graph-second");

    JsonNode firstProjection = graph(4);
    JsonNode repeatedProjection = graph(4);

    assertThat(firstProjection.path("nodes").size()).isEqualTo(4);
    assertThat(firstProjection.path("truncated").asBoolean()).isFalse();
    assertThat(firstProjection.path("nodes").toString())
        .contains("memo:" + firstMemo)
        .contains("memo:" + secondMemo)
        .contains("tag:" + OPERATING_SYSTEMS_TAG_ID)
        .contains("tag:" + ASSIGNMENT_TAG_ID);
    assertThat(firstProjection.path("edges").size()).isEqualTo(4);
    assertThat(repeatedProjection.path("nodes")).isEqualTo(firstProjection.path("nodes"));
    assertThat(repeatedProjection.path("edges")).isEqualTo(firstProjection.path("edges"));
    assertThat(repeatedProjection.path("projectionVersion"))
        .isEqualTo(firstProjection.path("projectionVersion"));
  }

  @Test
  void totalNodeLimitUsesOneExtraUniqueCandidateAndEdgesNeverDangle() throws Exception {
    createAppliedMemo("graph-bound-first");
    createAppliedMemo("graph-bound-second");

    JsonNode projection = graph(3);

    assertThat(projection.path("nodes").size()).isEqualTo(3);
    assertThat(projection.path("truncated").asBoolean()).isTrue();
    Set<String> nodeIds =
        java.util.stream.StreamSupport.stream(projection.path("nodes").spliterator(), false)
            .map(node -> node.path("id").asText())
            .collect(Collectors.toSet());
    projection
        .path("edges")
        .forEach(
            edge -> {
              assertThat(nodeIds).contains(edge.path("source").asText());
              assertThat(nodeIds).contains(edge.path("target").asText());
            });
  }

  @Test
  void mixedInformationAndTaskUsesApplicationSelectionAsStableRepresentative() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "graph-mixed-create", "mixed graph memo");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "graph-mixed-start", 1)).path("proposalId").asText());

    Map<String, Object> task = new LinkedHashMap<>();
    task.put("kind", "TASK");
    task.put("title", "Child task must not become the representative");
    task.put("due", null);
    Map<String, Object> information = new LinkedHashMap<>();
    information.put("kind", "INFORMATION");
    information.put("title", "Supporting information item");
    information.put("due", null);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision",
            1,
            "selectedType",
            "INFORMATION",
            "title",
            "Stable application title",
            "selectedTags",
            List.of(),
            "items",
            List.of(task, information));
    var applied = applyProposal(proposalId, "graph-mixed-apply", selection);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);

    JsonNode node = memoNode(graph(10), memoId);

    assertThat(node.path("label").asText()).isEqualTo("Stable application title");
    assertThat(node.path("memoType").asText()).isEqualTo("INFORMATION");
    assertThat(node.path("taskState").asText()).isEqualTo("TODO");
    assertThat(node.path("overdue").asBoolean()).isFalse();
  }

  @Test
  void anyOverdueActiveChildTaskMarksTheMemoOverdue() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "graph-overdue-create", "two task graph memo");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "graph-overdue-start", 1)).path("proposalId").asText());

    Map<String, Object> firstTask = new LinkedHashMap<>();
    firstTask.put("kind", "TASK");
    firstTask.put("title", "First child task");
    firstTask.put("due", null);
    Map<String, Object> secondTask = new LinkedHashMap<>();
    secondTask.put("kind", "TASK");
    secondTask.put("title", "Second child task");
    secondTask.put("due", null);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision",
            1,
            "selectedType",
            "TASK",
            "title",
            "Task collection title",
            "selectedTags",
            List.of(),
            "items",
            List.of(firstTask, secondTask));
    var applied = applyProposal(proposalId, "graph-overdue-apply", selection);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);

    List<UUID> taskIds =
        db.sql(
                """
                select i.id
                  from memo_items i
                  join task_details t
                    on t.memo_item_id = i.id
                   and t.owner_id = i.owner_id
                 where i.memo_id = :memoId
                   and i.owner_id = :ownerId
                   and i.archived_at is null
                 order by i.id
                """)
            .param("memoId", memoId)
            .param("ownerId", OWNER_ID)
            .query(UUID.class)
            .list();
    assertThat(taskIds).hasSize(2);
    db.sql(
            """
            update task_details
               set due_at_utc = current_timestamp + interval '1 day',
                   due_local_date = null
             where memo_item_id = :taskId
               and owner_id = :ownerId
            """)
        .param("taskId", taskIds.getFirst())
        .param("ownerId", OWNER_ID)
        .update();
    db.sql(
            """
            update task_details
               set due_at_utc = current_timestamp - interval '1 day',
                   due_local_date = null
             where memo_item_id = :taskId
               and owner_id = :ownerId
            """)
        .param("taskId", taskIds.getLast())
        .param("ownerId", OWNER_ID)
        .update();

    JsonNode node = memoNode(graph(10), memoId);

    assertThat(node.path("label").asText()).isEqualTo("Task collection title");
    assertThat(node.path("memoType").asText()).isEqualTo("TASK");
    assertThat(node.path("taskState").asText()).isEqualTo("TODO");
    assertThat(node.path("overdue").asBoolean()).isTrue();
  }

  @Test
  void homeUsesHardPinnedOverdueTodoAndRawRevisionRecencyPriority() throws Exception {
    UUID pinnedMemo = createAppliedMemo("graph-priority-pinned");
    UUID overdueMemo = createAppliedMemo("graph-priority-overdue");
    UUID todoMemo = createAppliedMemo("graph-priority-todo");
    UUID recentDoneMemo = createAppliedMemo("graph-priority-recent-done");

    pinMemo(pinnedMemo, "graph-priority-pin", true);
    markTaskDone(pinnedMemo);
    db.sql(
            "update task_details set due_at_utc=current_timestamp-interval '1 day', "
                + "due_local_date=null where memo_item_id=:taskId and owner_id=:ownerId")
        .param("taskId", taskId(overdueMemo))
        .param("ownerId", OWNER_ID)
        .update();
    markTaskDone(recentDoneMemo);
    setRevisionCreatedAt(pinnedMemo, "2026-01-01T00:00:00Z");
    setRevisionCreatedAt(overdueMemo, "2026-01-02T00:00:00Z");
    setRevisionCreatedAt(todoMemo, "2026-01-03T00:00:00Z");
    setRevisionCreatedAt(recentDoneMemo, "2026-08-01T00:00:00Z");

    JsonNode projection = graph(5);
    List<String> nodeIds =
        java.util.stream.StreamSupport.stream(projection.path("nodes").spliterator(), false)
            .filter(node -> "MEMO".equals(node.path("kind").asText()))
            .map(node -> node.path("id").asText())
            .toList();

    assertThat(nodeIds)
        .containsExactly(
            "memo:" + pinnedMemo,
            "memo:" + overdueMemo,
            "memo:" + todoMemo,
            "memo:" + recentDoneMemo);
    assertThat(projection.at("/nodes/0/pinned").asBoolean()).isTrue();
  }

  @Test
  void pinThenUnpinDoesNotRewriteRawRevisionRecency() throws Exception {
    UUID oldMemo = createAppliedMemo("graph-recency-old");
    UUID recentMemo = createAppliedMemo("graph-recency-recent");
    markTaskDone(oldMemo);
    markTaskDone(recentMemo);
    setRevisionCreatedAt(oldMemo, "2026-01-01T00:00:00Z");
    setRevisionCreatedAt(recentMemo, "2026-08-01T00:00:00Z");

    pinMemo(oldMemo, "graph-recency-pin", true);
    pinMemo(oldMemo, "graph-recency-unpin", false);

    JsonNode projection = graph(3);
    assertThat(projection.at("/nodes/0/id").asText()).isEqualTo("memo:" + recentMemo);
    assertThat(projection.at("/nodes/1/id").asText()).isEqualTo("memo:" + oldMemo);
  }

  @Test
  void nextTodoDueTakesPriorityOverRawRevisionRecencyAndNullDue() throws Exception {
    UUID soonerMemo = createAppliedMemo("graph-due-sooner");
    UUID laterMemo = createAppliedMemo("graph-due-later");
    UUID undatedMemo = createAppliedMemo("graph-due-undated");
    db.sql(
            "update task_details set due_at_utc=null, "
                + "due_local_date=(current_timestamp at time zone 'Asia/Seoul')::date+10, "
                + "source_time_zone='Asia/Seoul' "
                + "where memo_item_id=:taskId and owner_id=:ownerId")
        .param("taskId", taskId(soonerMemo))
        .param("ownerId", OWNER_ID)
        .update();
    db.sql(
            "update task_details set due_at_utc=current_timestamp+interval '20 days', "
                + "due_local_date=null where memo_item_id=:taskId and owner_id=:ownerId")
        .param("taskId", taskId(laterMemo))
        .param("ownerId", OWNER_ID)
        .update();
    setRevisionCreatedAt(soonerMemo, "2026-01-01T00:00:00Z");
    setRevisionCreatedAt(laterMemo, "2026-02-01T00:00:00Z");
    setRevisionCreatedAt(undatedMemo, "2026-08-01T00:00:00Z");

    JsonNode projection = graph(4);

    assertThat(projection.at("/nodes/0/id").asText()).isEqualTo("memo:" + soonerMemo);
    assertThat(projection.at("/nodes/1/id").asText()).isEqualTo("memo:" + laterMemo);
    assertThat(projection.at("/nodes/2/id").asText()).isEqualTo("memo:" + undatedMemo);
  }

  @Test
  void largeCorpusReservesAConnectedTagAndReportsOmittedMemos() throws Exception {
    for (int index = 0; index < 101; index++) {
      createAppliedMemoWithTags("graph-large-" + index, List.of(OPERATING_SYSTEMS_TAG_ID));
    }

    JsonNode projection = graph(100);
    long memoCount = countNodesOfKind(projection, "MEMO");
    long tagCount = countNodesOfKind(projection, "TAG");

    assertThat(projection.path("nodes").size()).isEqualTo(100);
    assertThat(memoCount).isEqualTo(99);
    assertThat(tagCount).isEqualTo(1);
    assertThat(projection.path("edges").size()).isEqualTo(99);
    assertThat(projection.path("truncated").asBoolean()).isTrue();
  }

  @Test
  void corpusWithoutTagsSafelyBackfillsTheMemoBudget() throws Exception {
    for (int index = 0; index < 5; index++) {
      createAppliedMemoWithTags("graph-no-tags-" + index, List.of());
    }

    JsonNode projection = graph(3);

    assertThat(projection.path("nodes").size()).isEqualTo(3);
    assertThat(countNodesOfKind(projection, "MEMO")).isEqualTo(3);
    assertThat(countNodesOfKind(projection, "TAG")).isZero();
    assertThat(projection.path("edges")).isEmpty();
    assertThat(projection.path("truncated").asBoolean()).isTrue();
  }

  @Test
  void untaggedPriorityMemoDoesNotMakeAnOmittedTaggedMemoLookComplete() throws Exception {
    UUID untaggedMemo = createAppliedMemoWithTags("graph-priority-no-tag", List.of());
    UUID taggedMemo =
        createAppliedMemoWithTags("graph-lower-priority-tagged", List.of(OPERATING_SYSTEMS_TAG_ID));
    pinMemo(untaggedMemo, "graph-priority-no-tag-pin", true);

    JsonNode projection = graph(2);

    assertThat(projection.path("nodes").size()).isEqualTo(1);
    assertThat(projection.at("/nodes/0/id").asText()).isEqualTo("memo:" + untaggedMemo);
    assertThat(projection.path("nodes").toString()).doesNotContain(taggedMemo.toString());
    assertThat(projection.path("edges")).isEmpty();
    assertThat(projection.path("truncated").asBoolean()).isTrue();
  }

  @Test
  void singleNodeLimitReportsTheSelectedMemosOmittedTag() throws Exception {
    UUID memoId =
        createAppliedMemoWithTags("graph-single-tagged", List.of(OPERATING_SYSTEMS_TAG_ID));

    JsonNode projection = graph(1);

    assertThat(projection.path("nodes").size()).isEqualTo(1);
    assertThat(projection.at("/nodes/0/id").asText()).isEqualTo("memo:" + memoId);
    assertThat(projection.path("edges")).isEmpty();
    assertThat(projection.path("truncated").asBoolean()).isTrue();
  }

  @Test
  void singleUntaggedMemoFitsAOneNodeProjectionWithoutFalseTruncation() throws Exception {
    UUID memoId = createAppliedMemoWithTags("graph-single-untagged", List.of());

    JsonNode projection = graph(1);

    assertThat(projection.path("nodes").size()).isEqualTo(1);
    assertThat(projection.at("/nodes/0/id").asText()).isEqualTo("memo:" + memoId);
    assertThat(projection.path("truncated").asBoolean()).isFalse();
  }

  @Test
  void memoBackfillRecomputesTagPriorityAgainstTheFinalMemoSet() throws Exception {
    for (int index = 0; index < 8; index++) {
      UUID memoId =
          createAppliedMemoWithTags(
              "graph-final-tag-rank-" + index,
              index == 0 ? List.of(OPERATING_SYSTEMS_TAG_ID) : List.of());
      pinMemo(memoId, "graph-final-tag-rank-pin-" + index, true);
    }
    UUID backfilledMemo =
        createAppliedMemoWithTags("graph-final-tag-rank-backfill", List.of(ASSIGNMENT_TAG_ID));

    JsonNode projection = graph(10);

    assertThat(projection.path("nodes").size()).isEqualTo(10);
    assertThat(countNodesOfKind(projection, "MEMO")).isEqualTo(9);
    assertThat(countNodesOfKind(projection, "TAG")).isEqualTo(1);
    assertThat(projection.path("nodes").toString())
        .contains("memo:" + backfilledMemo)
        .contains("tag:" + ASSIGNMENT_TAG_ID)
        .doesNotContain("tag:" + OPERATING_SYSTEMS_TAG_ID);
    assertThat(projection.path("edges").size()).isEqualTo(1);
    assertThat(projection.path("truncated").asBoolean()).isTrue();
  }

  @Test
  void limitedTagBudgetPrefersTheMostConnectedCanonicalTag() throws Exception {
    createAppliedMemoWithTags(
        "graph-tag-degree-both", List.of(OPERATING_SYSTEMS_TAG_ID, ASSIGNMENT_TAG_ID));
    createAppliedMemoWithTags("graph-tag-degree-os", List.of(OPERATING_SYSTEMS_TAG_ID));

    JsonNode projection = graph(3);

    assertThat(projection.path("nodes").size()).isEqualTo(3);
    assertThat(countNodesOfKind(projection, "MEMO")).isEqualTo(2);
    assertThat(countNodesOfKind(projection, "TAG")).isEqualTo(1);
    assertThat(projection.path("truncated").asBoolean()).isTrue();
    assertThat(projection.path("nodes").toString())
        .contains("tag:" + OPERATING_SYSTEMS_TAG_ID)
        .doesNotContain("tag:" + ASSIGNMENT_TAG_ID);
  }

  private long countNodesOfKind(JsonNode projection, String kind) {
    return java.util.stream.StreamSupport.stream(projection.path("nodes").spliterator(), false)
        .filter(node -> kind.equals(node.path("kind").asText()))
        .count();
  }

  private UUID createAppliedMemoWithTags(String keyPrefix, List<UUID> tagIds) throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, keyPrefix + "-create", keyPrefix + " task");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, keyPrefix + "-start", 1)).path("proposalId").asText());
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", keyPrefix + " task");
    item.put("due", null);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision",
            1,
            "selectedType",
            "TASK",
            "title",
            keyPrefix + " task",
            "selectedTags",
            tagIds.stream().map(tagId -> Map.of("existingTagId", tagId)).toList(),
            "items",
            List.of(item));
    var applied = applyProposal(proposalId, keyPrefix + "-apply", selection);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    return memoId;
  }

  private UUID taskId(UUID memoId) {
    return db.sql(
            "select t.memo_item_id from task_details t join memo_items i "
                + "on i.id=t.memo_item_id and i.owner_id=t.owner_id "
                + "where i.memo_id=:memoId and i.owner_id=:ownerId and i.archived_at is null")
        .param("memoId", memoId)
        .param("ownerId", OWNER_ID)
        .query(UUID.class)
        .single();
  }

  private void markTaskDone(UUID memoId) {
    db.sql(
            "update task_details set status='DONE', completed_at=current_timestamp "
                + "where memo_item_id=:taskId and owner_id=:ownerId")
        .param("taskId", taskId(memoId))
        .param("ownerId", OWNER_ID)
        .update();
  }

  private void setRevisionCreatedAt(UUID memoId, String instant) {
    db.sql(
            "update memo_revisions set created_at=cast(:createdAt as timestamptz) "
                + "where memo_id=:memoId and owner_id=:ownerId and revision=1")
        .param("createdAt", instant)
        .param("memoId", memoId)
        .param("ownerId", OWNER_ID)
        .update();
  }

  private void pinMemo(UUID memoId, String key, boolean pinned) throws Exception {
    var result =
        mvc.perform(
                patch("/api/v1/memos/{id}/pin", memoId)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("pinned", pinned))))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
  }

  private UUID createAppliedMemo(String keyPrefix) throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, keyPrefix + "-create", keyPrefix + " 작업");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, keyPrefix + "-start", 1)).path("proposalId").asText());
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", keyPrefix + " 작업");
    item.put("due", null);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision",
            1,
            "selectedType",
            "TASK",
            "title",
            keyPrefix + " 작업",
            "selectedTags",
            List.of(
                Map.of("existingTagId", OPERATING_SYSTEMS_TAG_ID),
                Map.of("existingTagId", ASSIGNMENT_TAG_ID)),
            "items",
            List.of(item));
    var applied = applyProposal(proposalId, keyPrefix + "-apply", selection);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    return memoId;
  }

  private JsonNode graph(int limit) throws Exception {
    var result =
        mvc.perform(get("/api/v1/graph/home").param("limit", Integer.toString(limit))).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    return response(result);
  }

  private JsonNode memoNode(JsonNode projection, UUID memoId) {
    for (JsonNode node : projection.path("nodes")) {
      if (("memo:" + memoId).equals(node.path("id").asText())) {
        return node;
      }
    }
    throw new AssertionError("Memo node was not present: " + memoId);
  }
}
