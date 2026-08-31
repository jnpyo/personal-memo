package local.personalmemo.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class EventReadBackIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void listsAndExportsOnlyCurrentOwnedActiveAppliedScheduledEventsWithoutLeakingProvenance()
      throws Exception {
    UUID timedId =
        seedEvent(
            OWNER_ID,
            "디스코드 접속",
            "ACTIVE",
            1,
            1,
            "APPLIED",
            Schedule.timed(Instant.parse("2026-09-01T09:00:00Z"), null, "Asia/Seoul"));
    UUID allDayId =
        seedEvent(
            OWNER_ID,
            "학회 일정",
            "ACTIVE",
            1,
            1,
            "APPLIED",
            Schedule.allDay(
                LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-05"), "Asia/Seoul"));

    seedEvent(OWNER_ID, "시간 없는 이벤트", "ACTIVE", 1, 1, "APPLIED", null);
    seedEvent(
        OWNER_ID,
        "되돌린 이벤트",
        "ACTIVE",
        1,
        1,
        "UNDONE",
        Schedule.timed(Instant.parse("2026-09-04T09:00:00Z"), null, "Asia/Seoul"));
    seedEvent(
        OWNER_ID,
        "휴지통 이벤트",
        "TRASHED",
        1,
        1,
        "APPLIED",
        Schedule.timed(Instant.parse("2026-09-05T09:00:00Z"), null, "Asia/Seoul"));
    seedEvent(
        OWNER_ID,
        "이전 revision 이벤트",
        "ACTIVE",
        2,
        1,
        "APPLIED",
        Schedule.timed(Instant.parse("2026-09-06T09:00:00Z"), null, "Asia/Seoul"));
    UUID archivedId =
        seedEvent(
            OWNER_ID,
            "보관된 이벤트",
            "ACTIVE",
            1,
            1,
            "APPLIED",
            Schedule.timed(Instant.parse("2026-09-06T10:00:00Z"), null, "Asia/Seoul"));
    db.sql("update memo_items set archived_at = :now where id = :id")
        .param("now", Timestamp.from(Instant.parse("2026-08-05T03:00:00Z")))
        .param("id", archivedId)
        .update();

    UUID foreignOwner = UUID.randomUUID();
    seedOwner(foreignOwner);
    seedEvent(
        foreignOwner,
        "다른 사용자의 비공개 일정",
        "ACTIVE",
        1,
        1,
        "APPLIED",
        Schedule.timed(Instant.parse("2026-09-07T09:00:00Z"), null, "Asia/Seoul"));

    MvcResult result = mvc.perform(get("/api/v1/events").param("limit", "100")).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    JsonNode events = response(result);
    assertThat(events).hasSize(2);

    JsonNode timed = events.get(0);
    assertThat(timed.path("id").asText()).isEqualTo(timedId.toString());
    assertThat(timed.path("title").asText()).isEqualTo("디스코드 접속");
    assertThat(timed.path("scheduleKind").asText()).isEqualTo("TIMED");
    assertThat(timed.path("startAt").asText()).isEqualTo("2026-09-01T09:00:00Z");
    assertThat(timed.path("endAt").isNull()).isTrue();
    assertThat(timed.path("startDate").isNull()).isTrue();
    assertThat(timed.path("endDateExclusive").isNull()).isTrue();
    assertThat(timed.path("sourceTimeZone").asText()).isEqualTo("Asia/Seoul");

    JsonNode allDay = events.get(1);
    assertThat(allDay.path("id").asText()).isEqualTo(allDayId.toString());
    assertThat(allDay.path("title").asText()).isEqualTo("학회 일정");
    assertThat(allDay.path("scheduleKind").asText()).isEqualTo("ALL_DAY");
    assertThat(allDay.path("startAt").isNull()).isTrue();
    assertThat(allDay.path("endAt").isNull()).isTrue();
    assertThat(allDay.path("startDate").asText()).isEqualTo("2026-09-03");
    assertThat(allDay.path("endDateExclusive").asText()).isEqualTo("2026-09-05");
    assertThat(allDay.path("sourceTimeZone").asText()).isEqualTo("Asia/Seoul");

    String responseBody = result.getResponse().getContentAsString();
    assertThat(responseBody)
        .doesNotContain(
            "raw-private-",
            "proposalId",
            "applicationId",
            "memoId",
            "memoRevision",
            "selectionJson");

    long eventDetailsBefore =
        db.sql("select count(*) from event_details").query(Long.class).single();
    long memoItemsBefore = db.sql("select count(*) from memo_items").query(Long.class).single();
    MvcResult firstCalendar = mvc.perform(get("/api/v1/events/calendar.ics")).andReturn();
    MvcResult secondCalendar = mvc.perform(get("/api/v1/events/calendar.ics")).andReturn();
    String calendar =
        new String(firstCalendar.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

    assertThat(firstCalendar.getResponse().getStatus()).isEqualTo(200);
    assertThat(firstCalendar.getResponse().getContentType())
        .isEqualTo("text/calendar;charset=UTF-8");
    assertThat(firstCalendar.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(firstCalendar.getResponse().getHeader("Content-Disposition"))
        .isEqualTo("attachment; filename=\"personal-memo-calendar.ics\"");
    assertThat(secondCalendar.getResponse().getContentAsByteArray())
        .containsExactly(firstCalendar.getResponse().getContentAsByteArray());
    assertThat(calendar)
        .contains(
            "BEGIN:VCALENDAR\r\n",
            "SUMMARY:디스코드 접속\r\n",
            "DTSTART:20260901T090000Z\r\n",
            "SUMMARY:학회 일정\r\n",
            "DTSTART;VALUE=DATE:20260903\r\n",
            "DTEND;VALUE=DATE:20260905\r\n",
            "SEQUENCE:0\r\n",
            "END:VCALENDAR\r\n")
        .doesNotContain(
            "시간 없는 이벤트",
            "되돌린 이벤트",
            "휴지통 이벤트",
            "이전 revision 이벤트",
            "보관된 이벤트",
            "다른 사용자의 비공개 일정",
            timedId.toString(),
            allDayId.toString(),
            "raw-private-",
            "VALARM",
            "proposalId",
            "applicationId",
            "memoId",
            "selectionJson");
    assertThat(count(calendar, "BEGIN:VEVENT\r\n")).isEqualTo(2);
    assertThat(db.sql("select count(*) from event_details").query(Long.class).single())
        .isEqualTo(eventDetailsBefore);
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single())
        .isEqualTo(memoItemsBefore);
  }

  @Test
  void appliesTheBoundedLimitAndRejectsValuesOutsideOneToOneHundred() throws Exception {
    seedEvent(
        OWNER_ID,
        "첫 일정",
        "ACTIVE",
        1,
        1,
        "APPLIED",
        Schedule.timed(Instant.parse("2026-09-01T09:00:00Z"), null, "Asia/Seoul"));
    UUID mostRecentlyApplied =
        seedEvent(
            OWNER_ID,
            "둘째 일정",
            "ACTIVE",
            1,
            1,
            "APPLIED",
            Schedule.timed(Instant.parse("2026-09-02T09:00:00Z"), null, "Asia/Seoul"));
    db.sql(
            """
            update analysis_applications
               set applied_at = :appliedAt
             where id = (select application_id from memo_items where id = :itemId)
            """)
        .param("appliedAt", Timestamp.from(Instant.parse("2026-08-06T02:00:00Z")))
        .param("itemId", mostRecentlyApplied)
        .update();

    MvcResult limited = mvc.perform(get("/api/v1/events").param("limit", "1")).andReturn();
    assertThat(limited.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(limited)).hasSize(1);
    assertThat(response(limited).get(0).path("id").asText())
        .isEqualTo(mostRecentlyApplied.toString());

    MvcResult tooLarge = mvc.perform(get("/api/v1/events").param("limit", "101")).andReturn();
    assertThat(tooLarge.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(tooLarge).path("code").asText()).isEqualTo("INVALID_EVENT_LIMIT");

    MvcResult empty = mvc.perform(get("/api/v1/events").param("limit", "0")).andReturn();
    assertThat(empty.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(empty).path("code").asText()).isEqualTo("INVALID_EVENT_LIMIT");
  }

  @Test
  void returnsNoContentForAnEmptyExportAndFailsClosedAboveOneHundredEvents() throws Exception {
    MvcResult empty = mvc.perform(get("/api/v1/events/calendar.ics")).andReturn();
    assertThat(empty.getResponse().getStatus()).isEqualTo(204);
    assertThat(empty.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(empty.getResponse().getContentAsByteArray()).isEmpty();

    for (int index = 0; index < 101; index++) {
      seedEvent(
          OWNER_ID,
          "일정 " + index,
          "ACTIVE",
          1,
          1,
          "APPLIED",
          Schedule.timed(
              Instant.parse("2026-09-01T09:00:00Z").plusSeconds(index), null, "Asia/Seoul"));
    }
    long before = db.sql("select count(*) from event_details").query(Long.class).single();

    MvcResult tooMany = mvc.perform(get("/api/v1/events/calendar.ics")).andReturn();

    assertThat(tooMany.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(tooMany).path("code").asText()).isEqualTo("ICALENDAR_EVENT_LIMIT_EXCEEDED");
    assertThat(tooMany.getResponse().getContentType()).isEqualTo("application/json");
    assertThat(db.sql("select count(*) from event_details").query(Long.class).single())
        .isEqualTo(before);
  }

  private void seedOwner(UUID ownerId) {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-05T02:00:00Z"));
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", ownerId)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:id,'Asia/Seoul',false)")
        .param("id", ownerId)
        .update();
  }

  private UUID seedEvent(
      UUID ownerId,
      String title,
      String memoStatus,
      int currentRevision,
      int itemRevision,
      String applicationStatus,
      Schedule schedule) {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-05T02:00:00Z"));
    UUID memoId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID proposalId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();

    db.sql(
            """
            insert into memos(
              id, owner_id, current_revision, status, pinned, created_at, updated_at, deleted_at
            ) values (
              :id, :ownerId, :currentRevision, :status, false, :now, :now, :deletedAt
            )
            """)
        .param("id", memoId)
        .param("ownerId", ownerId)
        .param("currentRevision", currentRevision)
        .param("status", memoStatus)
        .param("now", now)
        .param("deletedAt", "TRASHED".equals(memoStatus) ? now : null)
        .update();
    for (int revision = 1; revision <= currentRevision; revision++) {
      db.sql(
              """
              insert into memo_revisions(
                memo_id, owner_id, revision, content, content_hash, created_at, created_by,
                client_recorded_at, source_time_zone
              ) values (
                :memoId, :ownerId, :revision, :content, :contentHash, :now, :ownerId,
                :now, 'Asia/Seoul'
              )
              """)
          .param("memoId", memoId)
          .param("ownerId", ownerId)
          .param("revision", revision)
          .param("content", "raw-private-" + title + "-revision-" + revision)
          .param("contentHash", Integer.toHexString(revision).repeat(64))
          .param("now", now)
          .update();
    }
    db.sql(
            """
            insert into analysis_runs(
              id, owner_id, memo_id, memo_revision, route, status, schema_version,
              analyzer_version, ambiguity_reasons, created_at, completed_at,
              cloud_execution_contract_version
            ) values (
              :id, :ownerId, :memoId, :memoRevision, 'MOCK', 'APPLIED', '2',
              'fixture-v1', '[]', :now, :now, 'legacy-v0'
            )
            """)
        .param("id", runId)
        .param("ownerId", ownerId)
        .param("memoId", memoId)
        .param("memoRevision", itemRevision)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into analysis_proposals(
              id, owner_id, analysis_run_id, proposal_json, proposal_hash, created_at
            ) values (
              :id, :ownerId, :runId, '{"schemaVersion":"2"}', :proposalHash, :now
            )
            """)
        .param("id", proposalId)
        .param("ownerId", ownerId)
        .param("runId", runId)
        .param("proposalHash", "b".repeat(64))
        .param("now", now)
        .update();
    db.sql(
            """
            insert into analysis_applications(
              id, owner_id, proposal_id, memo_id, memo_revision, idempotency_key, status,
              selection_json, applied_at, undone_at
            ) values (
              :id, :ownerId, :proposalId, :memoId, :memoRevision, :idempotencyKey, :status,
              '{}', :now, :undoneAt
            )
            """)
        .param("id", applicationId)
        .param("ownerId", ownerId)
        .param("proposalId", proposalId)
        .param("memoId", memoId)
        .param("memoRevision", itemRevision)
        .param("idempotencyKey", "event-read-" + applicationId)
        .param("status", applicationStatus)
        .param("now", now)
        .param("undoneAt", "UNDONE".equals(applicationStatus) ? now : null)
        .update();
    db.sql(
            """
            insert into memo_items(
              id, owner_id, memo_id, memo_revision, application_id, kind, title, created_at
            ) values (
              :id, :ownerId, :memoId, :memoRevision, :applicationId, 'EVENT', :title, :now
            )
            """)
        .param("id", itemId)
        .param("ownerId", ownerId)
        .param("memoId", memoId)
        .param("memoRevision", itemRevision)
        .param("applicationId", applicationId)
        .param("title", title)
        .param("now", now)
        .update();
    if (schedule != null) {
      db.sql(
              """
              insert into event_details(
                memo_item_id, owner_id, item_kind, schedule_kind, start_at_utc, end_at_utc,
                start_local_date, end_local_date_exclusive, source_time_zone
              ) values (
                :itemId, :ownerId, 'EVENT', :scheduleKind, :startAt, :endAt,
                :startDate, :endDate, :sourceTimeZone
              )
              """)
          .param("itemId", itemId)
          .param("ownerId", ownerId)
          .param("scheduleKind", schedule.kind())
          .param("startAt", schedule.startAt() == null ? null : Timestamp.from(schedule.startAt()))
          .param("endAt", schedule.endAt() == null ? null : Timestamp.from(schedule.endAt()))
          .param(
              "startDate",
              schedule.startDate() == null ? null : java.sql.Date.valueOf(schedule.startDate()))
          .param(
              "endDate",
              schedule.endDateExclusive() == null
                  ? null
                  : java.sql.Date.valueOf(schedule.endDateExclusive()))
          .param("sourceTimeZone", schedule.sourceTimeZone())
          .update();
    }
    return itemId;
  }

  private record Schedule(
      String kind,
      Instant startAt,
      Instant endAt,
      LocalDate startDate,
      LocalDate endDateExclusive,
      String sourceTimeZone) {
    static Schedule timed(Instant startAt, Instant endAt, String sourceTimeZone) {
      return new Schedule("TIMED", startAt, endAt, null, null, sourceTimeZone);
    }

    static Schedule allDay(LocalDate startDate, LocalDate endDateExclusive, String sourceTimeZone) {
      return new Schedule("ALL_DAY", null, null, startDate, endDateExclusive, sourceTimeZone);
    }
  }

  private int count(String value, String needle) {
    return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
  }
}
