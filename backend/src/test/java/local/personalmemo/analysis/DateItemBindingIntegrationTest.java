package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class DateItemBindingIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void explicitBindingsApplyExactlyOnceAndUndoWithoutTouchingTheRawRevision() throws Exception {
    UUID memoId = UUID.randomUUID();
    String rawMemo = "보고서 초안은 11월 20일, 최종 제출은 11월 25일";
    assertThat(createMemo(memoId, "binding-create", rawMemo).getResponse().getStatus())
        .isEqualTo(201);

    var started = startAnalysis(memoId, "binding-start", 1);
    var startReplay = startAnalysis(memoId, "binding-start", 1);
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(startReplay)).isEqualTo(response(started));
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());

    var proposalResult =
        mvc.perform(
                get("/api/v1/analysis-proposals/{id}", proposalId)
                    .header("X-Analysis-Proposal-Schema-Version", "2"))
            .andReturn();
    assertThat(proposalResult.getResponse().getStatus()).isEqualTo(200);
    JsonNode proposal = response(proposalResult);
    assertThat(proposal.path("schemaVersion").asText()).isEqualTo("2");
    assertThat(proposal.path("dateCandidates")).hasSize(2);
    assertThat(proposal.path("itemCandidates")).hasSize(2);
    assertThat(proposal.at("/itemCandidates/0/dueDateCandidateId").asText()).isEqualTo("date-1");
    assertThat(proposal.at("/itemCandidates/1/dueDateCandidateId").asText()).isEqualTo("date-2");

    Map<String, Object> selection = reviewedSelection(proposal);
    var applied = applyProposal(proposalId, "binding-apply", selection);
    var applyReplay = applyProposal(proposalId, "binding-apply", selection);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(applyReplay)).isEqualTo(response(applied));
    UUID applicationId = UUID.fromString(response(applied).path("applicationId").asText());

    assertThat(
            db.sql(
                    "select t.due_local_date "
                        + "from task_details t join memo_items i on i.id=t.memo_item_id "
                        + "where i.memo_id=:memo and i.owner_id=:owner "
                        + "order by t.due_local_date")
                .param("memo", memoId)
                .param("owner", OWNER_ID)
                .query(LocalDate.class)
                .list())
        .containsExactly(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 25));
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isEqualTo(1L);
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isEqualTo(2L);
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single())
        .isEqualTo(2L);

    var undone = undoApplication(applicationId, "binding-undo");
    var undoReplay = undoApplication(applicationId, "binding-undo");
    assertThat(undone.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(undoReplay)).isEqualTo(response(undone));
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isZero();
    assertThat(
            db.sql(
                    "select content from memo_revisions "
                        + "where memo_id=:memo and revision=1 and owner_id=:owner")
                .param("memo", memoId)
                .param("owner", OWNER_ID)
                .query(String.class)
                .single())
        .isEqualTo(rawMemo);
    assertThat(
            db.sql(
                    "select count(*) from idempotency_records "
                        + "where idempotency_key in ('binding-start','binding-apply','binding-undo')")
                .query(Long.class)
                .single())
        .isEqualTo(3L);
  }

  @Test
  void appliedDueUsesTheImmutableMemoRevisionTimeZone() throws Exception {
    UUID memoId = UUID.randomUUID();
    String rawMemo = "보고서 초안은 11월 20일, 최종 제출은 11월 25일";
    var createBody =
        Map.of(
            "id",
            memoId,
            "content",
            rawMemo,
            "clientCreatedAt",
            OffsetDateTime.parse("2026-08-05T11:00:00-04:00"),
            "timeZone",
            "America/New_York");
    var created =
        mvc.perform(
                post("/api/v1/memos")
                    .header("Idempotency-Key", "binding-time-zone-create")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(createBody)))
            .andReturn();
    assertThat(created.getResponse().getStatus()).isEqualTo(201);

    var started = startAnalysis(memoId, "binding-time-zone-start", 1);
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposalResult =
        mvc.perform(
                get("/api/v1/analysis-proposals/{id}", proposalId)
                    .header("X-Analysis-Proposal-Schema-Version", "2"))
            .andReturn();
    JsonNode proposal = response(proposalResult);

    Map<String, Object> selection = reviewedSelection(proposal);
    var applied = applyProposal(proposalId, "binding-time-zone-apply", selection);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);

    assertThat(
            db.sql(
                    "select distinct t.source_time_zone "
                        + "from task_details t join memo_items i on i.id=t.memo_item_id "
                        + "where i.memo_id=:memo and i.owner_id=:owner")
                .param("memo", memoId)
                .param("owner", OWNER_ID)
                .query(String.class)
                .list())
        .containsExactly("America/New_York");
    assertThat(
            db.sql(
                    "select distinct a.selection_json #>> '{items,0,due,timeZone}' "
                        + "from analysis_applications a where a.memo_id=:memo and a.owner_id=:owner")
                .param("memo", memoId)
                .param("owner", OWNER_ID)
                .query(String.class)
                .list())
        .containsExactly("America/New_York");
  }

  @Test
  void exactDueOffsetMismatchWithImmutableRevisionZoneWritesNothing() throws Exception {
    UUID memoId = UUID.randomUUID();
    String rawMemo = "6시 디스코드 접속하기";
    var createBody =
        Map.of(
            "id",
            memoId,
            "content",
            rawMemo,
            "clientCreatedAt",
            OffsetDateTime.parse("2026-08-24T10:00:00-04:00"),
            "timeZone",
            "America/New_York");
    var created =
        mvc.perform(
                post("/api/v1/memos")
                    .header("Idempotency-Key", "binding-due-offset-create")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(createBody)))
            .andReturn();
    assertThat(created.getResponse().getStatus()).isEqualTo(201);

    var started = startAnalysis(memoId, "binding-due-offset-start", 1);
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposalResult =
        mvc.perform(
                get("/api/v1/analysis-proposals/{id}", proposalId)
                    .header("X-Analysis-Proposal-Schema-Version", "2"))
            .andReturn();
    assertThat(proposalResult.getResponse().getStatus()).isEqualTo(200);
    JsonNode proposal = response(proposalResult);
    String title = proposal.at("/suggestedTitle/value").asText().strip();
    String proposalCandidateId = proposal.at("/itemCandidates/0/candidateId").asText();

    Map<String, Object> due =
        Map.of(
            "surfaceText", "6시",
            "value", "2026-08-24T18:00:00+09:00",
            "precision", "EXACT_TIME",
            "timeZone", "Asia/Seoul",
            "timeSpecified", true);
    Map<String, Object> item =
        Map.of(
            "proposalCandidateId", proposalCandidateId,
            "kind", "TASK",
            "title", title,
            "due", due);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision", proposal.path("memoRevision").asInt(),
            "selectedType", "TASK",
            "title", title,
            "selectedTags", List.of(),
            "items", List.of(item),
            "selectedRelations", List.of());

    var rejected = applyProposal(proposalId, "binding-due-offset-apply", selection);

    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(rejected).path("code").asText()).isEqualTo("DUE_ZONE_OFFSET_MISMATCH");
    assertThat(
            db.sql(
                    "select count(*) from analysis_applications "
                        + "where memo_id=:memo and owner_id=:owner")
                .param("memo", memoId)
                .param("owner", OWNER_ID)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql("select count(*) from memo_items where memo_id=:memo and owner_id=:owner")
                .param("memo", memoId)
                .param("owner", OWNER_ID)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql(
                    "select count(*) from task_details t "
                        + "join memo_items i on i.id=t.memo_item_id "
                        + "where i.memo_id=:memo and i.owner_id=:owner")
                .param("memo", memoId)
                .param("owner", OWNER_ID)
                .query(Long.class)
                .single())
        .isZero();
  }

  private Map<String, Object> reviewedSelection(JsonNode proposal) {
    Map<String, JsonNode> datesById = new LinkedHashMap<>();
    for (JsonNode date : proposal.path("dateCandidates")) {
      datesById.put(date.path("candidateId").asText(), date);
    }

    String title = proposal.at("/suggestedTitle/value").asText().strip();
    List<Map<String, Object>> items = new ArrayList<>();
    int index = 0;
    for (JsonNode candidate : proposal.path("itemCandidates")) {
      JsonNode date = datesById.get(candidate.path("dueDateCandidateId").asText());
      Map<String, Object> due = new LinkedHashMap<>();
      due.put("surfaceText", date.path("surfaceText").asText());
      due.put("value", date.path("value").asText());
      due.put("precision", date.path("precision").asText());
      due.put("timeZone", "Asia/Seoul");
      due.put("timeSpecified", date.path("timeSpecified").asBoolean());

      Map<String, Object> item = new LinkedHashMap<>();
      item.put("kind", candidate.path("kind").asText());
      item.put("title", index++ == 0 ? title : candidate.path("title").asText().strip());
      item.put("due", due);
      items.add(item);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedMemoRevision", proposal.path("memoRevision").asInt());
    body.put("selectedType", "TASK");
    body.put("title", title);
    body.put("selectedTags", List.of());
    body.put("items", items);
    return body;
  }
}
