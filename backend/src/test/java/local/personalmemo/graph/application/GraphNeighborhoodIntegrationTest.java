package local.personalmemo.graph.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.graph.api.GraphDtos;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class GraphNeighborhoodIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID FOREIGN_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID FOREIGN_MEMO_ID =
      UUID.fromString("70000000-0000-0000-0000-000000000001");
  private static final UUID FOREIGN_TAG_ID =
      UUID.fromString("70000000-0000-0000-0000-000000000002");
  private static final String TEST_DIGEST = "abcdef0123456789".repeat(4);

  @Autowired private GraphNeighborhoodCursorCodec cursors;

  @Test
  void memoToTagUsesDefaultTwentyBoundAndCursorPagesWithoutDuplicates() throws Exception {
    AppliedMemo memo =
        createAppliedMemo(
            UUID.fromString("40000000-0000-0000-0000-000000000001"),
            "neighborhood-many-tags",
            List.of(),
            1);
    List<UUID> tagIds = addOrderedTags(memo, 23);

    MvcResult firstResult = neighborhood("MEMO", memo.memoId(), null, null);
    JsonNode first = response(firstResult);

    assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
    assertThat(firstResult.getResponse().getHeader("Cache-Control")).contains("no-store");
    assertThat(first.at("/center/id").asText()).isEqualTo("memo:" + memo.memoId());
    assertThat(first.path("neighbors").size()).isEqualTo(20);
    assertThat(first.path("edges").size()).isEqualTo(20);
    assertThat(first.path("truncated").asBoolean()).isTrue();
    assertThat(first.path("nextCursor").isTextual()).isTrue();
    assertThat(nodeEntityIds(first)).containsExactlyElementsOf(tagIds.subList(0, 20));
    assertNoDanglingEdges(first);

    String encodedCursor = first.path("nextCursor").asText();
    String rawCursor =
        new String(Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8);
    assertThat(rawCursor)
        .doesNotContain(
            "tag-19",
            "title",
            "canonicalName",
            "normalizedName",
            "dueAt",
            "nextDue",
            "pinnedRank",
            "todoRank",
            "revisionCreatedAt");

    JsonNode second = response(neighborhood("MEMO", memo.memoId(), 20, encodedCursor));
    List<UUID> secondIds = nodeEntityIds(second);

    assertThat(second.path("neighbors").size()).isEqualTo(3);
    assertThat(second.path("edges").size()).isEqualTo(3);
    assertThat(second.path("truncated").asBoolean()).isFalse();
    assertThat(second.path("nextCursor").isNull()).isTrue();
    assertThat(secondIds).containsExactlyElementsOf(tagIds.subList(20, 23));
    assertThat(new HashSet<>(nodeEntityIds(first))).doesNotContainAnyElementsOf(secondIds);
    assertNoDanglingEdges(second);
  }

  @Test
  void tagToMemoUsesHomePriorityAndFindsAnOldMemoOutsideTheHomeProjection() throws Exception {
    AppliedMemo pinned = createTaggedMemo("priority-pinned");
    AppliedMemo overdue = createTaggedMemo("priority-overdue");
    AppliedMemo todo = createTaggedMemo("priority-todo");
    AppliedMemo recentDone = createTaggedMemo("priority-recent-done");
    AppliedMemo oldDone = createTaggedMemo("priority-old-done");

    db.sql("update memos set pinned=true where id=:id and owner_id=:owner")
        .param("id", pinned.memoId())
        .param("owner", OWNER_ID)
        .update();
    markTaskDone(pinned.memoId());
    setTaskDue(overdue.memoId(), Instant.now().minus(Duration.ofDays(1)));
    setTaskDue(todo.memoId(), Instant.now().plus(Duration.ofDays(10)));
    markTaskDone(recentDone.memoId());
    markTaskDone(oldDone.memoId());
    setRevisionCreatedAt(pinned.memoId(), "2026-01-01T00:00:00Z");
    setRevisionCreatedAt(overdue.memoId(), "2026-01-02T00:00:00Z");
    setRevisionCreatedAt(todo.memoId(), "2026-01-03T00:00:00Z");
    setRevisionCreatedAt(recentDone.memoId(), "2026-08-01T00:00:00Z");
    setRevisionCreatedAt(oldDone.memoId(), "2025-01-01T00:00:00Z");

    JsonNode home =
        response(mvc.perform(get("/api/v1/graph/home").param("limit", "2")).andReturn());
    assertThat(home.path("nodes").toString()).doesNotContain(oldDone.memoId().toString());

    MvcResult result = neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 20, null);
    JsonNode page = response(result);
    List<UUID> ids = nodeEntityIds(page);

    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    assertThat(page.at("/center/id").asText()).isEqualTo("tag:" + OPERATING_SYSTEMS_TAG_ID);
    assertThat(ids)
        .containsExactly(
            pinned.memoId(),
            overdue.memoId(),
            todo.memoId(),
            recentDone.memoId(),
            oldDone.memoId());
    assertThat(response(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 20, null)).path("neighbors"))
        .isEqualTo(page.path("neighbors"));
    assertNoDanglingEdges(page);
  }

  @Test
  void memoKeysetHandlesEqualDueDescendingRevisionUuidAndNullDueBoundaries() throws Exception {
    UUID newestId = UUID.fromString("60000000-0000-0000-0000-000000000003");
    UUID firstEqualId = UUID.fromString("60000000-0000-0000-0000-000000000001");
    UUID secondEqualId = UUID.fromString("60000000-0000-0000-0000-000000000002");
    UUID nullDueId = UUID.fromString("60000000-0000-0000-0000-000000000004");
    List<UUID> ids = List.of(newestId, firstEqualId, secondEqualId, nullDueId);
    for (int index = 0; index < ids.size(); index++) {
      createAppliedMemo(ids.get(index), "boundary-" + index, List.of(OPERATING_SYSTEMS_TAG_ID), 1);
    }

    Instant equalDue = Instant.parse("2099-01-01T00:00:00Z");
    setTaskDue(newestId, equalDue);
    setTaskDue(firstEqualId, equalDue);
    setTaskDue(secondEqualId, equalDue);
    setRevisionCreatedAt(newestId, "2026-01-02T00:00:00Z");
    setRevisionCreatedAt(firstEqualId, "2026-01-01T00:00:00Z");
    setRevisionCreatedAt(secondEqualId, "2026-01-01T00:00:00Z");
    setRevisionCreatedAt(nullDueId, "2026-08-01T00:00:00Z");

    List<UUID> visited = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode page = response(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, cursor));
      assertThat(page.path("neighbors").size()).isEqualTo(1);
      visited.addAll(nodeEntityIds(page));
      cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
    } while (cursor != null);

    assertThat(visited).containsExactly(newestId, firstEqualId, secondEqualId, nullDueId);
    assertThat(new HashSet<>(visited)).hasSize(4);
  }

  @Test
  void cursorSnapshotFreezesTimeDerivedOverdueAcrossPages() throws Exception {
    Instant snapshot = Instant.parse("2026-08-11T00:00:00Z");
    UUID firstId = UUID.fromString("61000000-0000-0000-0000-000000000001");
    UUID secondId = UUID.fromString("61000000-0000-0000-0000-000000000002");
    createAppliedMemo(firstId, "snapshot-first", List.of(OPERATING_SYSTEMS_TAG_ID), 1);
    createAppliedMemo(secondId, "snapshot-second", List.of(OPERATING_SYSTEMS_TAG_ID), 1);
    setTaskDue(firstId, snapshot.plus(Duration.ofMinutes(10)));
    setTaskDue(secondId, snapshot.plus(Duration.ofMinutes(20)));

    MutableClock clock = new MutableClock(snapshot);
    GraphNeighborhoodService service =
        new GraphNeighborhoodService(db, () -> OWNER_ID, cursors, clock);
    GraphDtos.Neighborhood first = service.neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, null);
    clock.advance(Duration.ofMinutes(30));
    GraphDtos.Neighborhood second =
        service.neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, first.nextCursor());

    assertThat(first.neighbors()).extracting(GraphDtos.Node::id).containsExactly("memo:" + firstId);
    assertThat(second.neighbors())
        .extracting(GraphDtos.Node::id)
        .containsExactly("memo:" + secondId);
    assertThat(second.neighbors().getFirst().overdue()).isFalse();
  }

  @Test
  void tagCursorRejectsUnpinThatWouldOtherwiseSkipANewerMemo() throws Exception {
    UUID pinnedId = UUID.fromString("61100000-0000-0000-0000-000000000001");
    UUID newerId = UUID.fromString("61100000-0000-0000-0000-000000000002");
    createAppliedMemo(pinnedId, "digest-pinned", List.of(OPERATING_SYSTEMS_TAG_ID), 1);
    createAppliedMemo(newerId, "digest-newer", List.of(OPERATING_SYSTEMS_TAG_ID), 1);
    setRevisionCreatedAt(pinnedId, "2026-01-01T00:00:00Z");
    setRevisionCreatedAt(newerId, "2026-01-02T00:00:00Z");
    db.sql("update memos set pinned=true where id=:id and owner_id=:owner")
        .param("id", pinnedId)
        .param("owner", OWNER_ID)
        .update();

    JsonNode first = response(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, null));
    String cursor = first.path("nextCursor").asText();
    assertThat(nodeEntityIds(first)).containsExactly(pinnedId);

    db.sql("update memos set pinned=false where id=:id and owner_id=:owner")
        .param("id", pinnedId)
        .param("owner", OWNER_ID)
        .update();

    assertInvalidCursor(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, cursor));
    assertThat(nodeEntityIds(response(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, null))))
        .containsExactly(newerId);
  }

  @Test
  void tagCursorRejectsTaskDueCenterAndTopologyChanges() throws Exception {
    AppliedMemo first = createTaggedMemo("digest-task-first");
    AppliedMemo second = createTaggedMemo("digest-task-second");
    createTaggedMemo("digest-task-third");

    String dueCursor = continuationCursor("TAG", OPERATING_SYSTEMS_TAG_ID);
    setTaskDue(second.memoId(), Instant.parse("2099-01-01T00:00:00Z"));
    assertInvalidCursor(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, dueCursor));

    String taskCursor = continuationCursor("TAG", OPERATING_SYSTEMS_TAG_ID);
    markTaskDone(second.memoId());
    assertInvalidCursor(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, taskCursor));

    String centerCursor = continuationCursor("TAG", OPERATING_SYSTEMS_TAG_ID);
    db.sql(
            "update tags set canonical_name='Digest renamed center', "
                + "normalized_name='digest-renamed-center' where id=:id and owner_id=:owner")
        .param("id", OPERATING_SYSTEMS_TAG_ID)
        .param("owner", OWNER_ID)
        .update();
    assertInvalidCursor(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, centerCursor));

    String topologyCursor = continuationCursor("TAG", OPERATING_SYSTEMS_TAG_ID);
    db.sql("delete from item_tags where memo_item_id=:item and tag_id=:tag and owner_id=:owner")
        .param("item", first.itemIds().getFirst())
        .param("tag", OPERATING_SYSTEMS_TAG_ID)
        .param("owner", OWNER_ID)
        .update();
    assertInvalidCursor(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, topologyCursor));
  }

  @Test
  void memoCursorRejectsTagRenameAndLinkChanges() throws Exception {
    AppliedMemo memo =
        createAppliedMemo(
            UUID.fromString("61200000-0000-0000-0000-000000000001"),
            "digest-memo-tags",
            List.of(OPERATING_SYSTEMS_TAG_ID, ASSIGNMENT_TAG_ID),
            1);

    String renameCursor = continuationCursor("MEMO", memo.memoId());
    db.sql(
            "update tags set canonical_name='Digest renamed neighbor', "
                + "normalized_name='digest-renamed-neighbor' where id=:id and owner_id=:owner")
        .param("id", ASSIGNMENT_TAG_ID)
        .param("owner", OWNER_ID)
        .update();
    assertInvalidCursor(neighborhood("MEMO", memo.memoId(), 1, renameCursor));

    String topologyCursor = continuationCursor("MEMO", memo.memoId());
    db.sql("delete from item_tags where memo_item_id=:item and tag_id=:tag and owner_id=:owner")
        .param("item", memo.itemIds().getFirst())
        .param("tag", ASSIGNMENT_TAG_ID)
        .param("owner", OWNER_ID)
        .update();
    assertInvalidCursor(neighborhood("MEMO", memo.memoId(), 1, topologyCursor));
  }

  @Test
  void lifecycleFilteringAndDuplicateLinksStayCanonicalAndBounded() throws Exception {
    AppliedMemo duplicate =
        createAppliedMemo(
            UUID.fromString("62000000-0000-0000-0000-000000000001"),
            "lifecycle-duplicate",
            List.of(OPERATING_SYSTEMS_TAG_ID, ASSIGNMENT_TAG_ID),
            2);
    AppliedMemo trashed = createTaggedMemo("lifecycle-trashed");
    AppliedMemo undone = createTaggedMemo("lifecycle-undone");
    trashMemo(trashed.memoId(), "lifecycle-trash");
    undoApplication(undone.applicationId(), "lifecycle-undo");
    db.sql("update tags set state='INACTIVE' where id=:id and owner_id=:owner")
        .param("id", ASSIGNMENT_TAG_ID)
        .param("owner", OWNER_ID)
        .update();

    JsonNode tagPage = response(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 20, null));
    JsonNode memoPage = response(neighborhood("MEMO", duplicate.memoId(), 20, null));

    assertThat(nodeEntityIds(tagPage)).containsExactly(duplicate.memoId());
    assertThat(tagPage.path("edges").size()).isEqualTo(1);
    assertThat(nodeEntityIds(memoPage)).containsExactly(OPERATING_SYSTEMS_TAG_ID);
    assertThat(memoPage.path("edges").size()).isEqualTo(1);
    assertNotFound(neighborhood("TAG", ASSIGNMENT_TAG_ID, 20, "not-a-cursor"));
    assertNotFound(neighborhood("MEMO", trashed.memoId(), 20, "not-a-cursor"));
    assertNotFound(neighborhood("MEMO", undone.memoId(), 20, "not-a-cursor"));
  }

  @Test
  void foreignAndMissingCentersReturnUniformNotFoundBeforeMalformedCursorInspection()
      throws Exception {
    seedForeignGraph();
    UUID missing = UUID.fromString("70000000-0000-0000-0000-000000000099");

    List<MvcResult> results =
        List.of(
            neighborhood("MEMO", FOREIGN_MEMO_ID, 20, "not-a-cursor"),
            neighborhood("TAG", FOREIGN_TAG_ID, 20, "not-a-cursor"),
            neighborhood("MEMO", missing, 20, "not-a-cursor"),
            neighborhood("TAG", missing, 20, "not-a-cursor"));

    for (MvcResult result : results) {
      assertNotFound(result);
      assertThat(response(result).toString())
          .doesNotContain("Foreign memo", "Foreign tag", FOREIGN_OWNER_ID.toString());
    }
  }

  @Test
  void cursorKindCenterLastNeighborLifetimeAndRequestBoundsFailClosed() throws Exception {
    AppliedMemo first =
        createAppliedMemo(
            UUID.fromString("63000000-0000-0000-0000-000000000001"),
            "cursor-first",
            List.of(OPERATING_SYSTEMS_TAG_ID, ASSIGNMENT_TAG_ID),
            1);
    AppliedMemo second =
        createAppliedMemo(
            UUID.fromString("63000000-0000-0000-0000-000000000002"),
            "cursor-second",
            List.of(OPERATING_SYSTEMS_TAG_ID, ASSIGNMENT_TAG_ID),
            1);
    JsonNode firstPage = response(neighborhood("MEMO", first.memoId(), 1, null));
    String cursor = firstPage.path("nextCursor").asText();

    assertInvalidCursor(neighborhood("MEMO", second.memoId(), 1, cursor));
    assertInvalidCursor(neighborhood("TAG", OPERATING_SYSTEMS_TAG_ID, 1, cursor));
    assertInvalidCursor(neighborhood("MEMO", first.memoId(), 1, "not-a-cursor"));
    String foreignOwnerCursor =
        cursors.encode(
            FOREIGN_OWNER_ID,
            "MEMO",
            first.memoId(),
            GraphNeighborhoodService.TAG_SORT_SHAPE,
            Instant.now(),
            TEST_DIGEST,
            nodeEntityIds(firstPage).getFirst());
    assertInvalidCursor(neighborhood("MEMO", first.memoId(), 1, foreignOwnerCursor));

    UUID firstNeighbor = nodeEntityIds(firstPage).getFirst();
    db.sql("update tags set state='INACTIVE' where id=:id and owner_id=:owner")
        .param("id", firstNeighbor)
        .param("owner", OWNER_ID)
        .update();
    assertInvalidCursor(neighborhood("MEMO", first.memoId(), 1, cursor));

    assertInvalidLimit(neighborhood("MEMO", first.memoId(), 0, null));
    assertInvalidLimit(neighborhood("MEMO", first.memoId(), 21, null));
    MvcResult invalidKind = neighborhood("memo", first.memoId(), 20, null);
    assertThat(invalidKind.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(invalidKind).path("code").asText()).isEqualTo("INVALID_GRAPH_NODE_KIND");

    UUID liveNeighbor =
        OPERATING_SYSTEMS_TAG_ID.equals(firstNeighbor)
            ? ASSIGNMENT_TAG_ID
            : OPERATING_SYSTEMS_TAG_ID;
    Instant now = Instant.now();
    String expired =
        cursors.encode(
            OWNER_ID,
            "MEMO",
            first.memoId(),
            GraphNeighborhoodService.TAG_SORT_SHAPE,
            now.minus(Duration.ofHours(25)),
            TEST_DIGEST,
            liveNeighbor);
    String future =
        cursors.encode(
            OWNER_ID,
            "MEMO",
            first.memoId(),
            GraphNeighborhoodService.TAG_SORT_SHAPE,
            now.plus(Duration.ofMinutes(2)),
            TEST_DIGEST,
            liveNeighbor);
    String minimumInstant =
        cursors.encode(
            OWNER_ID,
            "MEMO",
            first.memoId(),
            GraphNeighborhoodService.TAG_SORT_SHAPE,
            Instant.MIN,
            TEST_DIGEST,
            liveNeighbor);
    String maximumInstant =
        cursors.encode(
            OWNER_ID,
            "MEMO",
            first.memoId(),
            GraphNeighborhoodService.TAG_SORT_SHAPE,
            Instant.MAX,
            TEST_DIGEST,
            liveNeighbor);
    assertInvalidCursor(neighborhood("MEMO", first.memoId(), 1, expired));
    assertInvalidCursor(neighborhood("MEMO", first.memoId(), 1, future));
    assertInvalidCursor(neighborhood("MEMO", first.memoId(), 1, minimumInstant));
    assertInvalidCursor(neighborhood("MEMO", first.memoId(), 1, maximumInstant));

    JsonNode memoCursorPage = response(neighborhood("TAG", liveNeighbor, 1, null));
    String memoCursor = memoCursorPage.path("nextCursor").asText();
    UUID lastMemoId = nodeEntityIds(memoCursorPage).getFirst();
    trashMemo(lastMemoId, "cursor-last-neighbor-trash");
    assertInvalidCursor(neighborhood("TAG", liveNeighbor, 1, memoCursor));
  }

  private AppliedMemo createTaggedMemo(String keyPrefix) throws Exception {
    return createAppliedMemo(UUID.randomUUID(), keyPrefix, List.of(OPERATING_SYSTEMS_TAG_ID), 1);
  }

  private AppliedMemo createAppliedMemo(
      UUID memoId, String keyPrefix, List<UUID> tagIds, int itemCount) throws Exception {
    assertThat(
            createMemo(memoId, keyPrefix + "-create", keyPrefix + " task")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, keyPrefix + "-start", 1)).path("proposalId").asText());
    List<Map<String, Object>> items = new ArrayList<>();
    for (int index = 0; index < itemCount; index++) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("kind", "TASK");
      item.put("title", keyPrefix + " task " + index);
      item.put("due", null);
      items.add(item);
    }
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision",
            1,
            "selectedType",
            "TASK",
            "title",
            keyPrefix + " title",
            "selectedTags",
            tagIds.stream().map(tagId -> Map.of("existingTagId", tagId)).toList(),
            "items",
            items);
    MvcResult applied = applyProposal(proposalId, keyPrefix + "-apply", selection);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    UUID applicationId = UUID.fromString(response(applied).path("applicationId").asText());
    List<UUID> itemIds =
        db.sql(
                "select id from memo_items where owner_id=:owner and application_id=:application order by id")
            .param("owner", OWNER_ID)
            .param("application", applicationId)
            .query(UUID.class)
            .list();
    return new AppliedMemo(memoId, applicationId, itemIds);
  }

  private List<UUID> addOrderedTags(AppliedMemo memo, int count) {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-11T00:00:00Z"));
    List<UUID> ids = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      UUID tagId =
          UUID.fromString("40000000-0000-0000-0000-" + String.format(Locale.ROOT, "%012d", index));
      String name = String.format(Locale.ROOT, "tag-%02d", index);
      db.sql(
              "insert into tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at) "
                  + "values(:id,:owner,:name,:name,'ACTIVE',:now,:now)")
          .param("id", tagId)
          .param("owner", OWNER_ID)
          .param("name", name)
          .param("now", now)
          .update();
      db.sql(
              "insert into item_tags(memo_item_id,owner_id,tag_id,application_id,source,confirmed_at) "
                  + "values(:item,:owner,:tag,:application,'USER',:now)")
          .param("item", memo.itemIds().getFirst())
          .param("owner", OWNER_ID)
          .param("tag", tagId)
          .param("application", memo.applicationId())
          .param("now", now)
          .update();
      ids.add(tagId);
    }
    return ids;
  }

  private void seedForeignGraph() {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-11T00:00:00Z"));
    UUID runId = UUID.fromString("71000000-0000-0000-0000-000000000001");
    UUID proposalId = UUID.fromString("71000000-0000-0000-0000-000000000002");
    UUID applicationId = UUID.fromString("71000000-0000-0000-0000-000000000003");
    UUID itemId = UUID.fromString("71000000-0000-0000-0000-000000000004");
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", FOREIGN_OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:id,'Asia/Seoul',false)")
        .param("id", FOREIGN_OWNER_ID)
        .update();
    db.sql(
            "insert into memos(id,owner_id,current_revision,status,pinned,created_at,updated_at) "
                + "values(:id,:owner,1,'ACTIVE',false,:now,:now)")
        .param("id", FOREIGN_MEMO_ID)
        .param("owner", FOREIGN_OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into memo_revisions(memo_id,owner_id,revision,content,content_hash,created_at,"
                + "created_by,client_recorded_at,source_time_zone) "
                + "values(:id,:owner,1,'Foreign raw',repeat('f',64),:now,:owner,:now,'Asia/Seoul')")
        .param("id", FOREIGN_MEMO_ID)
        .param("owner", FOREIGN_OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into analysis_runs(id,owner_id,memo_id,memo_revision,route,status,schema_version,"
                + "analyzer_version,ambiguity_reasons,created_at,completed_at,cloud_execution_contract_version) "
                + "values(:id,:owner,:memo,1,'MOCK','APPLIED','1','fake-v1','[]',:now,:now,'legacy-v0')")
        .param("id", runId)
        .param("owner", FOREIGN_OWNER_ID)
        .param("memo", FOREIGN_MEMO_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into analysis_proposals(id,owner_id,analysis_run_id,proposal_json,proposal_hash,created_at) "
                + "values(:id,:owner,:run,'{}',repeat('e',64),:now)")
        .param("id", proposalId)
        .param("owner", FOREIGN_OWNER_ID)
        .param("run", runId)
        .param("now", now)
        .update();
    db.sql(
            "insert into analysis_applications(id,owner_id,proposal_id,memo_id,memo_revision,idempotency_key,"
                + "status,selection_json,applied_at) values(:id,:owner,:proposal,:memo,1,'foreign-apply',"
                + "'APPLIED',cast(:selection as jsonb),:now)")
        .param("id", applicationId)
        .param("owner", FOREIGN_OWNER_ID)
        .param("proposal", proposalId)
        .param("memo", FOREIGN_MEMO_ID)
        .param("selection", "{\"title\":\"Foreign memo\",\"selectedType\":\"TASK\"}")
        .param("now", now)
        .update();
    db.sql(
            "insert into tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at) "
                + "values(:id,:owner,'Foreign tag','foreign tag','ACTIVE',:now,:now)")
        .param("id", FOREIGN_TAG_ID)
        .param("owner", FOREIGN_OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into memo_items(id,owner_id,memo_id,memo_revision,application_id,kind,title,created_at) "
                + "values(:id,:owner,:memo,1,:application,'TASK','Foreign item',:now)")
        .param("id", itemId)
        .param("owner", FOREIGN_OWNER_ID)
        .param("memo", FOREIGN_MEMO_ID)
        .param("application", applicationId)
        .param("now", now)
        .update();
    db.sql("insert into task_details(memo_item_id,owner_id,status) values(:id,:owner,'TODO')")
        .param("id", itemId)
        .param("owner", FOREIGN_OWNER_ID)
        .update();
    db.sql(
            "insert into item_tags(memo_item_id,owner_id,tag_id,application_id,source,confirmed_at) "
                + "values(:item,:owner,:tag,:application,'USER',:now)")
        .param("item", itemId)
        .param("owner", FOREIGN_OWNER_ID)
        .param("tag", FOREIGN_TAG_ID)
        .param("application", applicationId)
        .param("now", now)
        .update();
  }

  private MvcResult neighborhood(String kind, UUID centerId, Integer limit, String cursor)
      throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/v1/graph/nodes/{kind}/{id}/neighborhood", kind, centerId);
    if (limit != null) {
      request.param("limit", Integer.toString(limit));
    }
    if (cursor != null) {
      request.param("cursor", cursor);
    }
    return mvc.perform(request).andReturn();
  }

  private String continuationCursor(String kind, UUID centerId) throws Exception {
    JsonNode first = response(neighborhood(kind, centerId, 1, null));
    assertThat(first.path("neighbors").size()).isEqualTo(1);
    assertThat(first.path("truncated").asBoolean()).isTrue();
    assertThat(first.path("nextCursor").isTextual()).isTrue();
    return first.path("nextCursor").asText();
  }

  private List<UUID> nodeEntityIds(JsonNode page) {
    List<UUID> ids = new ArrayList<>();
    page.path("neighbors")
        .forEach(
            node -> {
              String id = node.path("id").asText();
              ids.add(UUID.fromString(id.substring(id.indexOf(':') + 1)));
            });
    return ids;
  }

  private void assertNoDanglingEdges(JsonNode page) {
    Set<String> nodeIds = new HashSet<>();
    nodeIds.add(page.at("/center/id").asText());
    page.path("neighbors").forEach(node -> nodeIds.add(node.path("id").asText()));
    page.path("edges")
        .forEach(
            edge -> {
              assertThat(nodeIds).contains(edge.path("source").asText());
              assertThat(nodeIds).contains(edge.path("target").asText());
            });
  }

  private void assertNotFound(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(response(result).path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(response(result).path("message").asText()).isEqualTo("Graph node was not found.");
  }

  private void assertInvalidCursor(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(result).path("code").asText()).isEqualTo("INVALID_GRAPH_CURSOR");
  }

  private void assertInvalidLimit(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(result).path("code").asText())
        .isEqualTo("INVALID_GRAPH_NEIGHBORHOOD_LIMIT");
  }

  private void markTaskDone(UUID memoId) {
    db.sql(
            "update task_details set status='DONE',completed_at=current_timestamp "
                + "where memo_item_id=:task and owner_id=:owner")
        .param("task", taskId(memoId))
        .param("owner", OWNER_ID)
        .update();
  }

  private void setTaskDue(UUID memoId, Instant due) {
    db.sql(
            "update task_details set due_at_utc=:due,due_local_date=null,source_time_zone='Asia/Seoul' "
                + "where memo_item_id=:task and owner_id=:owner")
        .param("due", Timestamp.from(due))
        .param("task", taskId(memoId))
        .param("owner", OWNER_ID)
        .update();
  }

  private UUID taskId(UUID memoId) {
    return db.sql(
            "select task.memo_item_id from task_details task join memo_items item "
                + "on item.id=task.memo_item_id and item.owner_id=task.owner_id "
                + "where item.memo_id=:memo and item.owner_id=:owner order by item.id limit 1")
        .param("memo", memoId)
        .param("owner", OWNER_ID)
        .query(UUID.class)
        .single();
  }

  private void setRevisionCreatedAt(UUID memoId, String instant) {
    db.sql(
            "update memo_revisions set created_at=:createdAt "
                + "where memo_id=:memo and owner_id=:owner and revision=1")
        .param("createdAt", Timestamp.from(Instant.parse(instant)))
        .param("memo", memoId)
        .param("owner", OWNER_ID)
        .update();
  }

  private record AppliedMemo(UUID memoId, UUID applicationId, List<UUID> itemIds) {}

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
