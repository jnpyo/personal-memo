package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@PostgresIntegration
class DatabaseOwnerConstraintIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void compositeForeignKeysRejectCrossOwnerRelationshipsAtTheDatabaseBoundary() throws Exception {
    UUID ownMemo = UUID.randomUUID();
    createMemo(ownMemo, "db-owner-own-create", "소유권 제약 작업");
    var started = startAnalysis(ownMemo, "db-owner-own-start", 1);
    UUID ownProposal = UUID.fromString(response(started).path("proposalId").asText());
    UUID ownApplication =
        UUID.fromString(
            response(applyProposal(ownProposal, "db-owner-own-apply", 1, "소유권 제약", null))
                .path("applicationId")
                .asText());
    UUID ownItem =
        db.sql("select id from memo_items where application_id=:applicationId")
            .param("applicationId", ownApplication)
            .query(UUID.class)
            .single();
    ForeignFlow foreign = seedForeignFlow();
    Timestamp now = Timestamp.from(Instant.now());

    assertRejected(
        () ->
            db.sql(
                    """
                    insert into analysis_runs(
                      id, owner_id, memo_id, memo_revision, route, status, schema_version,
                      analyzer_version, ambiguity_reasons, created_at, completed_at,
                      cloud_execution_contract_version
                    ) values (
                      :id, :ownerId, :memoId, 1, 'MOCK', 'REVIEW_REQUIRED', '1',
                      'fake-v1', '[]', :now, :now, 'legacy-v0'
                    )
                    """)
                .param("id", UUID.randomUUID())
                .param("ownerId", OWNER_ID)
                .param("memoId", foreign.memoId())
                .param("now", now)
                .update());

    assertRejected(
        () ->
            db.sql(
                    """
                    insert into analysis_proposals(
                      id, owner_id, analysis_run_id, proposal_json, proposal_hash, created_at
                    ) values (
                      :id, :ownerId, :runId, '{}', repeat('c',64), :now
                    )
                    """)
                .param("id", UUID.randomUUID())
                .param("ownerId", OWNER_ID)
                .param("runId", foreign.runId())
                .param("now", now)
                .update());

    assertRejected(
        () ->
            db.sql(
                    """
                    insert into analysis_applications(
                      id, owner_id, proposal_id, memo_id, memo_revision, idempotency_key,
                      status, selection_json, applied_at
                    ) values (
                      :id, :ownerId, :proposalId, :memoId, 1, :key,
                      'APPLIED', '{}', :now
                    )
                    """)
                .param("id", UUID.randomUUID())
                .param("ownerId", OWNER_ID)
                .param("proposalId", foreign.proposalId())
                .param("memoId", ownMemo)
                .param("key", "cross-owner-application")
                .param("now", now)
                .update());

    assertRejected(
        () ->
            db.sql(
                    """
                    insert into memo_items(
                      id, owner_id, memo_id, memo_revision, application_id, kind, title, created_at
                    ) values (
                      :id, :ownerId, :memoId, 1, :applicationId, 'TASK', 'cross owner', :now
                    )
                    """)
                .param("id", UUID.randomUUID())
                .param("ownerId", OWNER_ID)
                .param("memoId", ownMemo)
                .param("applicationId", foreign.applicationId())
                .param("now", now)
                .update());

    assertRejected(
        () ->
            db.sql(
                    "insert into task_details(memo_item_id,owner_id,status) "
                        + "values(:itemId,:ownerId,'TODO')")
                .param("itemId", foreign.itemId())
                .param("ownerId", OWNER_ID)
                .update());

    assertRejected(
        () -> insertTimedEventDetail(foreign.itemId(), OWNER_ID, "2026-08-24T09:00:00Z"));
    assertRejected(() -> insertTimedEventDetail(ownItem, OWNER_ID, "2026-08-24T09:00:00Z"));

    assertRejected(
        () ->
            db.sql(
                    """
                    insert into item_tags(
                      memo_item_id, owner_id, tag_id, application_id, source, confirmed_at
                    ) values (
                      :itemId, :ownerId, :tagId, :applicationId, 'USER', :now
                    )
                    """)
                .param("itemId", ownItem)
                .param("ownerId", OWNER_ID)
                .param("tagId", foreign.tagId())
                .param("applicationId", ownApplication)
                .param("now", now)
                .update());

    assertRejected(
        () ->
            insertRelation(
                ownApplication, 0, ownItem, "MEMO", foreign.memoId(), null, "RELATED_TO", now));
    assertRejected(
        () ->
            insertRelation(
                ownApplication, 1, ownItem, "TAG", null, foreign.tagId(), "REFERENCES", now));
    assertRejected(
        () ->
            insertRelation(
                ownApplication, 2, ownItem, "MEMO", ownMemo, ASSIGNMENT_TAG_ID, "DEPENDS_ON", now));
    assertRejected(
        () ->
            insertRelation(
                ownApplication, 3, foreign.itemId(), "MEMO", ownMemo, null, "CONTINUES", now));

    insertRelation(ownApplication, 4, ownItem, "MEMO", ownMemo, null, "RELATED_TO", now);
    assertRejected(
        () -> insertRelation(ownApplication, 5, ownItem, "MEMO", ownMemo, null, "RELATED_TO", now));

    assertRejected(
        () ->
            db.sql(
                    """
                    insert into tags(
                      id, owner_id, canonical_name, normalized_name, state, created_at,
                      updated_at, created_by_application_id
                    ) values (
                      :id, :ownerId, 'cross creator', 'cross creator', 'ACTIVE', :now,
                      :now, :applicationId
                    )
                    """)
                .param("id", UUID.randomUUID())
                .param("ownerId", OWNER_ID)
                .param("applicationId", foreign.applicationId())
                .param("now", now)
                .update());
  }

  private ForeignFlow seedForeignFlow() {
    UUID owner = UUID.randomUUID();
    UUID memo = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID proposal = UUID.randomUUID();
    UUID application = UUID.randomUUID();
    UUID item = UUID.randomUUID();
    UUID tag = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());

    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", owner)
        .param("now", now)
        .update();
    db.sql(
            "insert into memos(id,owner_id,current_revision,status,pinned,created_at,updated_at) "
                + "values(:id,:owner,1,'ACTIVE',false,:now,:now)")
        .param("id", memo)
        .param("owner", owner)
        .param("now", now)
        .update();
    db.sql(
            "insert into memo_revisions(memo_id,owner_id,revision,content,content_hash,created_at,created_by,client_recorded_at,source_time_zone) "
                + "values(:memo,:owner,1,'foreign',repeat('f',64),:now,:owner,:now,'Asia/Seoul')")
        .param("memo", memo)
        .param("owner", owner)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into analysis_runs(
              id, owner_id, memo_id, memo_revision, route, status, schema_version,
              analyzer_version, ambiguity_reasons, created_at, completed_at,
              cloud_execution_contract_version
            ) values (
              :id, :owner, :memo, 1, 'MOCK', 'APPLIED', '1', 'fake-v1', '[]', :now, :now,
              'legacy-v0'
            )
            """)
        .param("id", run)
        .param("owner", owner)
        .param("memo", memo)
        .param("now", now)
        .update();
    db.sql(
            "insert into analysis_proposals(id,owner_id,analysis_run_id,proposal_json,proposal_hash,created_at) "
                + "values(:id,:owner,:run,'{}',repeat('e',64),:now)")
        .param("id", proposal)
        .param("owner", owner)
        .param("run", run)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into analysis_applications(
              id, owner_id, proposal_id, memo_id, memo_revision, idempotency_key,
              status, selection_json, applied_at
            ) values (
              :id, :owner, :proposal, :memo, 1, :key, 'APPLIED', '{}', :now
            )
            """)
        .param("id", application)
        .param("owner", owner)
        .param("proposal", proposal)
        .param("memo", memo)
        .param("key", "foreign-valid-" + application)
        .param("now", now)
        .update();
    db.sql(
            "insert into tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at) "
                + "values(:id,:owner,'foreign tag',:name,'ACTIVE',:now,:now)")
        .param("id", tag)
        .param("owner", owner)
        .param("name", "foreign tag " + tag)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into memo_items(
              id, owner_id, memo_id, memo_revision, application_id, kind, title, created_at
            ) values (
              :id, :owner, :memo, 1, :application, 'TASK', 'foreign task', :now
            )
            """)
        .param("id", item)
        .param("owner", owner)
        .param("memo", memo)
        .param("application", application)
        .param("now", now)
        .update();
    return new ForeignFlow(owner, memo, run, proposal, application, item, tag);
  }

  private void assertRejected(Runnable insert) {
    assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insertTimedEventDetail(UUID itemId, UUID ownerId, String startAt) {
    db.sql(
            """
            insert into event_details(
              memo_item_id, owner_id, item_kind, schedule_kind, start_at_utc, source_time_zone
            ) values (
              :itemId, :ownerId, 'EVENT', 'TIMED', cast(:startAt as timestamptz), 'Asia/Seoul'
            )
            """)
        .param("itemId", itemId)
        .param("ownerId", ownerId)
        .param("startAt", startAt)
        .update();
  }

  private void insertRelation(
      UUID applicationId,
      int proposalIndex,
      UUID sourceItemId,
      String targetType,
      UUID targetMemoId,
      UUID targetTagId,
      String relationType,
      Timestamp confirmedAt) {
    db.sql(
            """
            insert into memo_item_relations(
              application_id,
              proposal_relation_index,
              owner_id,
              source_memo_item_id,
              target_type,
              target_memo_id,
              target_tag_id,
              relation_type,
              confirmed_at
            ) values (
              :applicationId,
              :proposalIndex,
              :ownerId,
              :sourceItemId,
              :targetType,
              :targetMemoId,
              :targetTagId,
              :relationType,
              :confirmedAt
            )
            """)
        .param("applicationId", applicationId)
        .param("proposalIndex", proposalIndex)
        .param("ownerId", OWNER_ID)
        .param("sourceItemId", sourceItemId)
        .param("targetType", targetType)
        .param("targetMemoId", targetMemoId)
        .param("targetTagId", targetTagId)
        .param("relationType", relationType)
        .param("confirmedAt", confirmedAt)
        .update();
  }

  private record ForeignFlow(
      UUID ownerId,
      UUID memoId,
      UUID runId,
      UUID proposalId,
      UUID applicationId,
      UUID itemId,
      UUID tagId) {}
}
