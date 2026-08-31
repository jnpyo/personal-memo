package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class EventScheduleApplicationIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void timedEventAppliesIdempotentlyUsesRevisionZoneAndUndoesWithoutInventingAnEnd()
      throws Exception {
    UUID memoId = UUID.randomUUID();
    String rawMemo = "오늘 오후 6시 디스코드 접속하기";
    createMemoInZone(memoId, "event-timed-create", rawMemo, "America/New_York");
    UUID proposalId = proposalId(memoId, "event-timed-start");
    Map<String, Object> body =
        eventSelection(
            "디스코드 접속하기", schedule("TIMED", "2026-08-24T18:00:00-04:00", null, "Asia/Seoul"));

    var applied = applyProposal(proposalId, "event-timed-apply", body);
    var replay = applyProposal(proposalId, "event-timed-apply", body);

    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(replay)).isEqualTo(response(applied));
    UUID applicationId = UUID.fromString(response(applied).path("applicationId").asText());
    assertThat(
            db.sql("select start_at_utc from event_details")
                .query(OffsetDateTime.class)
                .single()
                .toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-08-24T18:00:00-04:00").toInstant());
    assertThat(db.sql("select end_at_utc is null from event_details").query(Boolean.class).single())
        .isTrue();
    assertThat(db.sql("select source_time_zone from event_details").query(String.class).single())
        .isEqualTo("America/New_York");
    assertThat(
            db.sql(
                    "select selection_json #>> '{items,0,eventSchedule,timeZone}' "
                        + "from analysis_applications where id=:id")
                .param("id", applicationId)
                .query(String.class)
                .single())
        .isEqualTo("America/New_York");
    assertThat(
            db.sql(
                    "select selection_json #>> '{selectionSchemaVersion}' "
                        + "from analysis_applications where id=:id")
                .param("id", applicationId)
                .query(String.class)
                .single())
        .isEqualTo("2");

    Map<String, Object> changedBody =
        eventSelection(
            "디스코드 접속하기", schedule("TIMED", "2026-08-24T19:00:00-04:00", null, "America/New_York"));
    var changed = applyProposal(proposalId, "event-timed-apply", changedBody);
    assertThat(changed.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(changed).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    var undone = undoApplication(applicationId, "event-timed-undo");
    var undoReplay = undoApplication(applicationId, "event-timed-undo");
    assertThat(undone.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(undoReplay)).isEqualTo(response(undone));
    assertThat(db.sql("select count(*) from event_details").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(
            db.sql(
                    "select content from memo_revisions "
                        + "where memo_id=:memo and revision=1 and owner_id=:owner")
                .param("memo", memoId)
                .param("owner", OWNER_ID)
                .query(String.class)
                .single())
        .isEqualTo(rawMemo);
  }

  @Test
  void allDayExclusiveEndPersistsWhileInvalidSecondItemRollsBackEverything() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "event-all-day-create", "8월 24일부터 26일까지 휴가");
    UUID proposalId = proposalId(memoId, "event-all-day-start");
    Map<String, Object> body =
        eventSelection("휴가", schedule("ALL_DAY", "2026-08-24", "2026-08-27", "Asia/Seoul"));

    var applied = applyProposal(proposalId, "event-all-day-apply", body);

    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    assertThat(db.sql("select start_local_date from event_details").query(LocalDate.class).single())
        .isEqualTo(LocalDate.of(2026, 8, 24));
    assertThat(
            db.sql("select end_local_date_exclusive from event_details")
                .query(LocalDate.class)
                .single())
        .isEqualTo(LocalDate.of(2026, 8, 27));
    assertThat(
            db.sql("select start_at_utc is null and end_at_utc is null from event_details")
                .query(Boolean.class)
                .single())
        .isTrue();

    UUID invalidMemoId = UUID.randomUUID();
    createMemo(invalidMemoId, "event-invalid-create", "일정 두 개");
    UUID invalidProposalId = proposalId(invalidMemoId, "event-invalid-start");
    Map<String, Object> invalid =
        eventSelection("첫 일정", schedule("TIMED", "2026-08-24T18:00:00+09:00", null, "Asia/Seoul"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> validItems = (List<Map<String, Object>>) invalid.get("items");
    Map<String, Object> invalidSecond = new LinkedHashMap<>();
    invalidSecond.put("proposalCandidateId", null);
    invalidSecond.put("kind", "EVENT");
    invalidSecond.put("title", "둘째 일정");
    invalidSecond.put("due", null);
    invalidSecond.put(
        "eventSchedule",
        schedule("TIMED", "2026-08-24T20:00:00+09:00", "2026-08-24T19:00:00+09:00", "Asia/Seoul"));
    invalid.put("items", List.of(validItems.getFirst(), invalidSecond));

    var rejected = applyProposal(invalidProposalId, "event-invalid-apply", invalid);

    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(rejected).path("code").asText()).isEqualTo("INVALID_EVENT_SCHEDULE_RANGE");
    assertThat(
            db.sql("select count(*) from memo_items where memo_id=:memo")
                .param("memo", invalidMemoId)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void existingTitleOnlyEventRemainsValidButGetsNoSyntheticSchedule() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "event-title-only-create", "언젠가 동창회");
    UUID proposalId = proposalId(memoId, "event-title-only-start");
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("proposalCandidateId", null);
    item.put("kind", "EVENT");
    item.put("title", "동창회");
    item.put("due", null);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedMemoRevision", 1);
    body.put("selectedType", "EVENT");
    body.put("title", "동창회");
    body.put("selectedTags", List.of());
    body.put("items", List.of(item));
    body.put("selectedRelations", List.of());

    var applied = applyProposal(proposalId, "event-title-only-apply", body);

    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    assertThat(db.sql("select count(*) from event_details").query(Long.class).single()).isZero();
    assertThat(
            db.sql("select kind from memo_items where memo_id=:memo")
                .param("memo", memoId)
                .query(String.class)
                .single())
        .isEqualTo("EVENT");
  }

  @Test
  void fractionalTimedScheduleFailsBeforeAnyCanonicalWrite() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "event-fractional-create", "오후 6시 회의");
    UUID proposalId = proposalId(memoId, "event-fractional-start");

    var rejected =
        applyProposal(
            proposalId,
            "event-fractional-apply",
            eventSelection(
                "회의", schedule("TIMED", "2026-08-25T18:00:00.100+09:00", null, "Asia/Seoul")));

    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(rejected).path("code").asText())
        .isEqualTo("INVALID_EVENT_SCHEDULE_PRECISION");
    assertThat(
            db.sql("select count(*) from memo_items where memo_id=:memo")
                .param("memo", memoId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(db.sql("select count(*) from event_details").query(Long.class).single()).isZero();
  }

  @Test
  void unknownEventScheduleFieldsFailClosedBeforeAnyCanonicalWrite() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "event-unknown-field-create", "내일 오후 6시 회의");
    UUID proposalId = proposalId(memoId, "event-unknown-field-start");
    Map<String, Object> schedule =
        schedule("TIMED", "2026-08-25T18:00:00+09:00", null, "Asia/Seoul");
    schedule.put("durationMinutes", 60);

    var rejected =
        applyProposal(proposalId, "event-unknown-field-apply", eventSelection("회의", schedule));

    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(rejected).path("code").asText()).isEqualTo("MALFORMED_JSON");
    assertThat(
            db.sql("select count(*) from memo_items where memo_id=:memo")
                .param("memo", memoId)
                .query(Long.class)
                .single())
        .isZero();
  }

  private UUID proposalId(UUID memoId, String key) throws Exception {
    var started = startAnalysis(memoId, key, 1);
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    return UUID.fromString(response(started).path("proposalId").asText());
  }

  private void createMemoInZone(UUID memoId, String key, String content, String timeZone)
      throws Exception {
    var body =
        Map.of(
            "id",
            memoId,
            "content",
            content,
            "clientCreatedAt",
            OffsetDateTime.parse("2026-08-24T12:00:00-04:00"),
            "timeZone",
            timeZone);
    var created =
        mvc.perform(
                post("/api/v1/memos")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(body)))
            .andReturn();
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
  }

  private Map<String, Object> eventSelection(String title, Map<String, Object> schedule) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("proposalCandidateId", null);
    item.put("kind", "EVENT");
    item.put("title", title);
    item.put("due", null);
    item.put("eventSchedule", schedule);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedMemoRevision", 1);
    body.put("selectedType", "EVENT");
    body.put("title", title);
    body.put("selectedTags", List.of());
    body.put("items", List.of(item));
    body.put("selectedRelations", List.of());
    body.put("selectionSchemaVersion", "2");
    return body;
  }

  private Map<String, Object> schedule(String mode, String start, String end, String timeZone) {
    Map<String, Object> schedule = new LinkedHashMap<>();
    schedule.put("mode", mode);
    schedule.put("start", start);
    schedule.put("end", end);
    schedule.put("timeZone", timeZone);
    return schedule;
  }
}
