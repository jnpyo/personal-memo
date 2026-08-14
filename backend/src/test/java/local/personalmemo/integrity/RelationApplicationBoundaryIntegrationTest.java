package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@PostgresIntegration
class RelationApplicationBoundaryIntegrationTest extends PostgresIntegrationTestSupport {

  @Autowired private DataSource dataSource;

  @Test
  void requiresExplicitSelectionForNonemptyProposalButExplicitEmptyRejectsAll() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "relation-required-target", "Target memo");
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-required",
            List.of(relation("MEMO", targetMemoId, "RELATED_TO", 0.9)));

    var missing =
        applyProposal(
            proposalId,
            "relation-required-missing",
            applyBody("Source item", "item-1", null, false));

    assertThat(missing.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(missing).path("code").asText()).isEqualTo("RELATION_SELECTION_REQUIRED");
    assertNoApplicationWrites();

    var rejectAll =
        applyProposal(
            proposalId,
            "relation-required-empty",
            applyBody("Source item", "item-1", List.of(), true));

    assertThat(rejectAll.getResponse().getStatus()).isEqualTo(200);
    assertThat(db.sql("select count(*) from memo_item_relations").query(Long.class).single())
        .isZero();
  }

  @Test
  void relationFreeLegacyOmissionSucceedsButExplicitNullIsMalformed() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "relation-null-create", "Call supplier tomorrow");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "relation-null-start", 1)).path("proposalId").asText());

    var explicitNull =
        applyProposal(
            proposalId, "relation-explicit-null", applyBody("Source item", null, null, true));

    assertThat(explicitNull.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(explicitNull).path("code").asText()).isEqualTo("MALFORMED_JSON");
    assertNoApplicationWrites();

    var legacyOmission =
        applyProposal(
            proposalId, "relation-legacy-omission", applyBody("Source item", null, null, false));

    assertThat(legacyOmission.getResponse().getStatus()).isEqualTo(200);
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from memo_item_relations").query(Long.class).single())
        .isZero();
  }

  @Test
  void rejectsUnknownAuthorityFieldsAtEveryRelationApplyRequestBoundary() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "relation-unknown-target", "Target memo");
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-unknown",
            List.of(relation("MEMO", targetMemoId, "RELATED_TO", 0.9)));
    ObjectNode base =
        objectNode(applyBody("Source item", "item-1", List.of(Map.of("proposalIndex", 0)), true));

    ObjectNode rootAuthority = base.deepCopy();
    rootAuthority.put("ownerId", OWNER_ID.toString());

    ObjectNode itemAuthority = base.deepCopy();
    ((ObjectNode) itemAuthority.at("/items/0"))
        .put("sourceMemoItemId", UUID.randomUUID().toString());

    ObjectNode relationAuthority = base.deepCopy();
    ((ObjectNode) relationAuthority.at("/selectedRelations/0"))
        .put("targetId", targetMemoId.toString())
        .put("relationType", "RELATED_TO")
        .put("score", 1.0);

    ObjectNode tagAuthority = base.deepCopy();
    ((ObjectNode) tagAuthority.at("/selectedTags/0"))
        .put("ownerId", OWNER_ID.toString())
        .put("state", "ACTIVE");

    ObjectNode dueAuthority = base.deepCopy();
    ObjectNode due =
        json.createObjectNode()
            .put("surfaceText", "tomorrow")
            .put("value", "2026-08-06")
            .put("precision", "DATE_ONLY")
            .put("timeZone", "Asia/Seoul")
            .put("timeSpecified", false)
            .put("dueAtUtc", "2026-08-05T15:00:00Z");
    ((ObjectNode) dueAuthority.at("/items/0")).set("due", due);

    List<ObjectNode> poisonedRequests =
        List.of(rootAuthority, itemAuthority, relationAuthority, tagAuthority, dueAuthority);
    for (int index = 0; index < poisonedRequests.size(); index++) {
      MvcResult rejected =
          applyProposalJson(
              proposalId, "relation-unknown-field-" + index, poisonedRequests.get(index));
      assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
      assertThat(response(rejected).path("code").asText()).isEqualTo("MALFORMED_JSON");
    }
    assertNoApplicationWrites();
  }

  @Test
  void persistsSelectedDirectedRelationsAndResolvedSelectionThenUndoRemovesThem() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "relation-persist-target", "Related target memo");
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-persist",
            List.of(
                relation("MEMO", targetMemoId, "RELATED_TO", 0.91),
                relation("TAG", ASSIGNMENT_TAG_ID, "REFERENCES", 0.73)));

    var applied =
        applyProposal(
            proposalId,
            "relation-persist-apply",
            applyBody(
                "Source item",
                "item-1",
                List.of(Map.of("proposalIndex", 0), Map.of("proposalIndex", 1)),
                true));

    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    UUID applicationId = UUID.fromString(response(applied).path("applicationId").asText());
    List<JsonNode> relations =
        db
            .sql(
                """
                select jsonb_build_object(
                         'proposalIndex', proposal_relation_index,
                         'sourceItemId', source_memo_item_id,
                         'targetType', target_type,
                         'targetMemoId', target_memo_id,
                         'targetTagId', target_tag_id,
                         'relationType', relation_type
                       )::text
                  from memo_item_relations
                 where application_id = :applicationId
                   and owner_id = :ownerId
                 order by proposal_relation_index
                """)
            .param("applicationId", applicationId)
            .param("ownerId", OWNER_ID)
            .query(String.class)
            .list()
            .stream()
            .map(this::read)
            .toList();
    assertThat(relations).hasSize(2);
    assertThat(relations.get(0).path("targetMemoId").asText()).isEqualTo(targetMemoId.toString());
    assertThat(relations.get(1).path("targetTagId").asText())
        .isEqualTo(ASSIGNMENT_TAG_ID.toString());

    JsonNode selection =
        read(
            db.sql(
                    """
                    select selection_json::text
                      from analysis_applications
                     where id = :applicationId
                    """)
                .param("applicationId", applicationId)
                .query(String.class)
                .single());
    JsonNode storedRelation = selection.at("/selectedRelations/0");
    assertThat(storedRelation.path("proposalIndex").asInt()).isZero();
    assertThat(storedRelation.path("sourceProposalCandidateId").asText()).isEqualTo("item-1");
    assertThat(storedRelation.path("sourceProposalItemIndex").asInt()).isZero();
    assertThat(storedRelation.path("sourceMemoItemId").asText())
        .isEqualTo(relations.get(0).path("sourceItemId").asText());
    JsonNode storedItem = selection.at("/items/0");
    assertThat(storedItem.path("proposalCandidateId").asText())
        .isEqualTo(storedRelation.path("sourceProposalCandidateId").asText());
    assertThat(storedItem.path("memoItemId").asText())
        .isEqualTo(storedRelation.path("sourceMemoItemId").asText());
    assertThat(storedRelation.path("targetType").asText()).isEqualTo("MEMO");
    assertThat(storedRelation.path("targetId").asText()).isEqualTo(targetMemoId.toString());
    assertThat(storedRelation.path("relationType").asText()).isEqualTo("RELATED_TO");
    assertThat(storedRelation.has("score")).isFalse();
    assertThat(
            db.sql("select count(*) from item_tags where owner_id=:ownerId and tag_id=:targetTag")
                .param("ownerId", OWNER_ID)
                .param("targetTag", ASSIGNMENT_TAG_ID)
                .query(Long.class)
                .single())
        .isZero();

    var undone = undoApplication(applicationId, "relation-persist-undo");

    assertThat(undone.getResponse().getStatus()).isEqualTo(200);
    assertThat(
            db.sql("select count(*) from memo_item_relations where application_id=:applicationId")
                .param("applicationId", applicationId)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void unavailableTargetFailsWithTypedConflictAndRollsBack() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "relation-unavailable-target", "Unavailable target");
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-unavailable",
            List.of(relation("MEMO", targetMemoId, "DEPENDS_ON", 0.8)));
    trashMemo(targetMemoId, "relation-unavailable-trash");

    var rejected =
        applyProposal(
            proposalId,
            "relation-unavailable-apply",
            applyBody("Source item", "item-1", List.of(Map.of("proposalIndex", 0)), true));

    assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(rejected).path("code").asText()).isEqualTo("RELATION_TARGET_UNAVAILABLE");
    assertNoApplicationWrites();
  }

  @Test
  void applyWaitsForConcurrentTargetUpdateThenRejectsCommittedInactiveTarget() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "relation-target-race-target", "Racing target");
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-target-race",
            List.of(relation("MEMO", targetMemoId, "DEPENDS_ON", 0.8)));

    try (var executor = Executors.newSingleThreadExecutor();
        Connection updater = dataSource.getConnection()) {
      updater.setAutoCommit(false);
      int blockerPid = backendPid(updater);
      boolean committed = false;
      try {
        try (PreparedStatement statement =
            updater.prepareStatement(
                "update memos set status='TRASHED', updated_at=? "
                    + "where id=? and owner_id=? and status='ACTIVE'")) {
          statement.setTimestamp(1, Timestamp.from(Instant.parse("2026-08-05T03:00:00Z")));
          statement.setObject(2, targetMemoId);
          statement.setObject(3, OWNER_ID);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        var apply =
            executor.submit(
                () ->
                    applyProposal(
                        proposalId,
                        "relation-target-race-apply",
                        applyBody(
                            "Source item", "item-1", List.of(Map.of("proposalIndex", 0)), true)));
        boolean applyWaitedForTargetRow = awaitBlockedBy(blockerPid, 5, TimeUnit.SECONDS);
        updater.commit();
        committed = true;

        MvcResult rejected = apply.get(15, TimeUnit.SECONDS);
        assertThat(applyWaitedForTargetRow).isTrue();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(response(rejected).path("code").asText())
            .isEqualTo("RELATION_TARGET_UNAVAILABLE");
      } finally {
        if (!committed) {
          updater.rollback();
        }
      }
    }

    assertThat(
            db.sql("select status from memos where id=:memoId and owner_id=:ownerId")
                .param("memoId", targetMemoId)
                .param("ownerId", OWNER_ID)
                .query(String.class)
                .single())
        .isEqualTo("TRASHED");
    assertNoApplicationWrites();
  }

  @Test
  void selectedRelationSourceMustMapToAnAppliedProposalCandidate() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "relation-source-target", "Target");
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-source",
            List.of(relation("MEMO", targetMemoId, "RELATED_TO", 0.8)));

    var rejected =
        applyProposal(
            proposalId,
            "relation-source-apply",
            applyBody("Unmapped item", null, List.of(Map.of("proposalIndex", 0)), true));

    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(rejected).path("code").asText()).isEqualTo("RELATION_SOURCE_NOT_APPLIED");
    assertNoApplicationWrites();
  }

  @Test
  void crossOwnerTargetFailsWithSameUnavailableConflictAndRollsBack() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID foreignTargetMemoId = seedForeignMemo();
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-cross-owner",
            List.of(relation("MEMO", foreignTargetMemoId, "REFERENCES", 0.8)));

    var rejected =
        applyProposal(
            proposalId,
            "relation-cross-owner-apply",
            applyBody("Source item", "item-1", List.of(Map.of("proposalIndex", 0)), true));

    assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(rejected).path("code").asText()).isEqualTo("RELATION_TARGET_UNAVAILABLE");
    assertNoApplicationWrites();
  }

  @Test
  void historicalDuplicateProposalFailsEvenWhenOnlyOneDuplicateIndexIsSelected() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "relation-duplicate-target", "Duplicate target");
    Map<String, Object> duplicate = relation("MEMO", targetMemoId, "CONTINUES", 0.9);
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId, "relation-duplicate", List.of(duplicate, new LinkedHashMap<>(duplicate)));

    var rejected =
        applyProposal(
            proposalId,
            "relation-duplicate-apply",
            applyBody("Source item", "item-1", List.of(Map.of("proposalIndex", 0)), true));

    assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(rejected).path("code").asText()).isEqualTo("PROPOSAL_CHANGED");
    assertNoApplicationWrites();
  }

  private UUID createProposalWithRelations(
      UUID sourceMemoId, String keyPrefix, List<Map<String, Object>> relations) throws Exception {
    createMemo(sourceMemoId, keyPrefix + "-create", "Call supplier tomorrow");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(sourceMemoId, keyPrefix + "-start", 1))
                .path("proposalId")
                .asText());
    db.sql(
            """
            update analysis_proposals
               set proposal_json = jsonb_set(
                 proposal_json,
                 '{relationCandidates}',
                 cast(:relations as jsonb),
                 false
               )
             where id = :proposalId
               and owner_id = :ownerId
            """)
        .param("relations", json.writeValueAsString(relations))
        .param("proposalId", proposalId)
        .param("ownerId", OWNER_ID)
        .update();
    return proposalId;
  }

  private Map<String, Object> relation(
      String targetType, UUID targetId, String relationType, double score) {
    return Map.of(
        "sourceCandidateId", "item-1",
        "targetType", targetType,
        "targetId", targetId,
        "relationType", relationType,
        "score", score);
  }

  private Map<String, Object> applyBody(
      String title,
      String proposalCandidateId,
      Object selectedRelations,
      boolean includeSelection) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("proposalCandidateId", proposalCandidateId);
    item.put("kind", "TASK");
    item.put("title", title);
    item.put("due", null);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedMemoRevision", 1);
    body.put("selectedType", "TASK");
    body.put("title", title);
    body.put("selectedTags", List.of(Map.of("existingTagId", OPERATING_SYSTEMS_TAG_ID)));
    body.put("items", List.of(item));
    if (includeSelection) {
      body.put("selectedRelations", selectedRelations);
    }
    return body;
  }

  private MvcResult applyProposalJson(UUID proposalId, String key, JsonNode body) throws Exception {
    return mvc.perform(
            post("/api/v1/analysis-proposals/{id}/apply", proposalId)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(json.writeValueAsBytes(body)))
        .andReturn();
  }

  private ObjectNode objectNode(Object value) {
    try {
      return (ObjectNode) json.readTree(json.writeValueAsBytes(value));
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private int backendPid(Connection connection) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("select pg_backend_pid()");
        var resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        throw new AssertionError("PostgreSQL did not return a backend PID.");
      }
      return resultSet.getInt(1);
    }
  }

  private boolean awaitBlockedBy(int blockerPid, long timeout, TimeUnit timeUnit)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      long waiting =
          db.sql(
                  "select count(*) from pg_stat_activity activity "
                      + "where :blockerPid = any(pg_blocking_pids(activity.pid))")
              .param("blockerPid", blockerPid)
              .query(Long.class)
              .single();
      if (waiting > 0) {
        return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private JsonNode read(String value) {
    try {
      return json.readTree(value);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
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
              :id, :ownerId, 1, 'ACTIVE', false, :now, :now
            )
            """)
        .param("id", memoId)
        .param("ownerId", ownerId)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into memo_revisions(
              memo_id, owner_id, revision, content, content_hash, created_at, created_by,
              client_recorded_at, source_time_zone
            ) values (
              :memoId, :ownerId, 1, 'Foreign target', repeat('f', 64), :now, :ownerId,
              :now, 'Asia/Seoul'
            )
            """)
        .param("memoId", memoId)
        .param("ownerId", ownerId)
        .param("now", now)
        .update();
    return memoId;
  }

  private void assertNoApplicationWrites() {
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isZero();
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from item_tags").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from memo_item_relations").query(Long.class).single())
        .isZero();
    assertThat(
            db.sql("select count(*) from idempotency_records " + "where operation='ANALYSIS_APPLY'")
                .query(Long.class)
                .single())
        .isZero();
  }
}
