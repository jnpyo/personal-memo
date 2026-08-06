package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class OwnerIsolationIntegrationTest extends PostgresIntegrationTestSupport {

  private final UUID otherOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final UUID otherMemoId = UUID.fromString("30000000-0000-0000-0000-000000000002");
  private final UUID otherRunId = UUID.fromString("31000000-0000-0000-0000-000000000002");
  private final UUID otherProposalId = UUID.fromString("32000000-0000-0000-0000-000000000002");
  private final UUID otherApplicationId = UUID.fromString("33000000-0000-0000-0000-000000000002");
  private final UUID otherItemId = UUID.fromString("34000000-0000-0000-0000-000000000002");
  private final UUID otherTagId = UUID.fromString("35000000-0000-0000-0000-000000000002");

  @BeforeEach
  void seedAnotherOwnersCompleteFlow() {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-05T02:00:00Z"));
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", otherOwnerId)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:id,'Asia/Seoul',false)")
        .param("id", otherOwnerId)
        .update();
    db.sql(
            "insert into memos(id,owner_id,current_revision,status,pinned,created_at,updated_at) "
                + "values(:id,:owner,1,'ACTIVE',false,:now,:now)")
        .param("id", otherMemoId)
        .param("owner", otherOwnerId)
        .param("now", now)
        .update();
    db.sql(
            "insert into memo_revisions(memo_id,owner_id,revision,content,content_hash,created_at,created_by,client_recorded_at,source_time_zone) "
                + "values(:id,:owner,1,'다른 사용자의 비공개 메모',repeat('a',64),:now,:owner,:now,'Asia/Seoul')")
        .param("id", otherMemoId)
        .param("owner", otherOwnerId)
        .param("now", now)
        .update();
    db.sql(
            "insert into analysis_runs(id,owner_id,memo_id,memo_revision,route,status,"
                + "schema_version,analyzer_version,ambiguity_reasons,created_at,completed_at) "
                + "values(:id,:owner,:memo,1,'MOCK','APPLIED','1','fake-v1','[]',:now,:now)")
        .param("id", otherRunId)
        .param("owner", otherOwnerId)
        .param("memo", otherMemoId)
        .param("now", now)
        .update();
    db.sql(
            "insert into analysis_proposals(id,owner_id,analysis_run_id,proposal_json,proposal_hash,created_at) "
                + "values(:id,:owner,:run,'{\"schemaVersion\":\"1\"}',repeat('b',64),:now)")
        .param("id", otherProposalId)
        .param("owner", otherOwnerId)
        .param("run", otherRunId)
        .param("now", now)
        .update();
    db.sql(
            "insert into analysis_applications(id,owner_id,proposal_id,memo_id,memo_revision,"
                + "idempotency_key,status,selection_json,applied_at) "
                + "values(:id,:owner,:proposal,:memo,1,'other-owner-apply','APPLIED','{}',:now)")
        .param("id", otherApplicationId)
        .param("owner", otherOwnerId)
        .param("proposal", otherProposalId)
        .param("memo", otherMemoId)
        .param("now", now)
        .update();
    db.sql(
            "insert into tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at) "
                + "values(:id,:owner,'비공개 태그','비공개 태그','ACTIVE',:now,:now)")
        .param("id", otherTagId)
        .param("owner", otherOwnerId)
        .param("now", now)
        .update();
    db.sql(
            "insert into memo_items(id,owner_id,memo_id,memo_revision,application_id,kind,title,created_at) "
                + "values(:id,:owner,:memo,1,:application,'TASK','다른 사용자의 작업',:now)")
        .param("id", otherItemId)
        .param("owner", otherOwnerId)
        .param("memo", otherMemoId)
        .param("application", otherApplicationId)
        .param("now", now)
        .update();
    db.sql("insert into task_details(memo_item_id,owner_id,status) values(:id,:owner,'TODO')")
        .param("id", otherItemId)
        .param("owner", otherOwnerId)
        .update();
    db.sql(
            "insert into item_tags(memo_item_id,owner_id,tag_id,application_id,source,confirmed_at) "
                + "values(:item,:owner,:tag,:application,'USER',:now)")
        .param("item", otherItemId)
        .param("owner", otherOwnerId)
        .param("tag", otherTagId)
        .param("application", otherApplicationId)
        .param("now", now)
        .update();
  }

  @Test
  void readsAndProjectionsNeverExposeAnotherOwnersRecords() throws Exception {
    var memo = mvc.perform(get("/api/v1/memos/{id}", otherMemoId)).andReturn();
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", otherProposalId)).andReturn();
    var tasks = mvc.perform(get("/api/v1/tasks")).andReturn();
    var graph = mvc.perform(get("/api/v1/graph/home")).andReturn();

    assertNotFound(memo);
    assertNotFound(proposal);
    assertThat(tasks.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(tasks).toString()).doesNotContain(otherItemId.toString());
    assertThat(response(tasks).toString()).doesNotContain("다른 사용자의 작업");
    assertThat(graph.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(graph).toString()).doesNotContain(otherMemoId.toString());
    assertThat(response(graph).toString()).doesNotContain(otherTagId.toString());
  }

  @Test
  void mutationsCannotReferenceAnotherOwnersMemoTagOrApplication() throws Exception {
    var startForeign = startAnalysis(otherMemoId, "start-foreign-memo", 1);
    assertNotFound(startForeign);

    var undoForeign = undoApplication(otherApplicationId, "undo-foreign-application");
    assertNotFound(undoForeign);
    var updateForeignTask =
        mvc.perform(
                patch("/api/v1/tasks/{id}", otherItemId)
                    .header("Idempotency-Key", "update-foreign-task")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("status", "DONE"))))
            .andReturn();
    assertNotFound(updateForeignTask);

    UUID ownMemoId = UUID.randomUUID();
    createMemo(ownMemoId, "create-own-memo", "내 메모");
    UUID ownProposalId =
        UUID.fromString(
            response(startAnalysis(ownMemoId, "start-own-memo", 1)).path("proposalId").asText());
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", "외부 태그가 연결되면 안 됨");
    item.put("due", null);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision",
            1,
            "selectedType",
            "TASK",
            "title",
            "외부 태그 차단",
            "selectedTags",
            List.of(Map.of("existingTagId", otherTagId)),
            "items",
            List.of(item));

    var crossOwnerApply = applyProposal(ownProposalId, "cross-owner-tag-apply", selection);
    assertNotFound(crossOwnerApply);
    assertThat(
            db.sql("select count(*) from analysis_applications where owner_id=:owner")
                .param("owner", OWNER_ID)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql("select count(*) from memo_items where owner_id=:owner")
                .param("owner", OWNER_ID)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql(
                    "select count(*) from item_tags it join memo_items i on i.id=it.memo_item_id "
                        + "where i.owner_id=:owner and it.tag_id=:foreignTag")
                .param("owner", OWNER_ID)
                .param("foreignTag", otherTagId)
                .query(Long.class)
                .single())
        .isZero();
  }

  private void assertNotFound(org.springframework.test.web.servlet.MvcResult result)
      throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(response(result).path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
  }
}
