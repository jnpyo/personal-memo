package local.personalmemo.calendar;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class CalendarFeedTestData {
  private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-08-05T02:00:00Z"));

  private CalendarFeedTestData() {}

  static Seed timed(JdbcClient db, UUID ownerId, String title, Instant startAt, Instant endAt) {
    return seed(db, ownerId, title, "TIMED", startAt, endAt, null, null);
  }

  static Seed allDay(
      JdbcClient db, UUID ownerId, String title, LocalDate startDate, LocalDate endDateExclusive) {
    return seed(db, ownerId, title, "ALL_DAY", null, null, startDate, endDateExclusive);
  }

  private static Seed seed(
      JdbcClient db,
      UUID ownerId,
      String title,
      String scheduleKind,
      Instant startAt,
      Instant endAt,
      LocalDate startDate,
      LocalDate endDateExclusive) {
    UUID memoId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID proposalId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    db.sql(
            """
            insert into memos(
              id, owner_id, current_revision, status, pinned, created_at, updated_at, deleted_at
            ) values (
              :id, :ownerId, 1, 'ACTIVE', false, :now, :now, null
            )
            """)
        .param("id", memoId)
        .param("ownerId", ownerId)
        .param("now", NOW)
        .update();
    db.sql(
            """
            insert into memo_revisions(
              memo_id, owner_id, revision, content, content_hash, created_at, created_by,
              client_recorded_at, source_time_zone
            ) values (
              :memoId, :ownerId, 1, :content, :contentHash, :now, :ownerId,
              :now, 'Asia/Seoul'
            )
            """)
        .param("memoId", memoId)
        .param("ownerId", ownerId)
        .param("content", "fixture-event-" + title)
        .param("contentHash", "1".repeat(64))
        .param("now", NOW)
        .update();
    db.sql(
            """
            insert into analysis_runs(
              id, owner_id, memo_id, memo_revision, route, status, schema_version,
              analyzer_version, ambiguity_reasons, created_at, completed_at,
              cloud_execution_contract_version
            ) values (
              :id, :ownerId, :memoId, 1, 'MOCK', 'APPLIED', '2',
              'fixture-v1', '[]', :now, :now, 'legacy-v0'
            )
            """)
        .param("id", runId)
        .param("ownerId", ownerId)
        .param("memoId", memoId)
        .param("now", NOW)
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
        .param("now", NOW)
        .update();
    db.sql(
            """
            insert into analysis_applications(
              id, owner_id, proposal_id, memo_id, memo_revision, idempotency_key, status,
              selection_json, applied_at, undone_at
            ) values (
              :id, :ownerId, :proposalId, :memoId, 1, :idempotencyKey, 'APPLIED',
              '{}', :now, null
            )
            """)
        .param("id", applicationId)
        .param("ownerId", ownerId)
        .param("proposalId", proposalId)
        .param("memoId", memoId)
        .param("idempotencyKey", "calendar-fixture-" + applicationId)
        .param("now", NOW)
        .update();
    db.sql(
            """
            insert into memo_items(
              id, owner_id, memo_id, memo_revision, application_id, kind, title, created_at
            ) values (
              :id, :ownerId, :memoId, 1, :applicationId, 'EVENT', :title, :now
            )
            """)
        .param("id", eventId)
        .param("ownerId", ownerId)
        .param("memoId", memoId)
        .param("applicationId", applicationId)
        .param("title", title)
        .param("now", NOW)
        .update();
    db.sql(
            """
            insert into event_details(
              memo_item_id, owner_id, item_kind, schedule_kind, start_at_utc, end_at_utc,
              start_local_date, end_local_date_exclusive, source_time_zone
            ) values (
              :eventId, :ownerId, 'EVENT', :scheduleKind, :startAt, :endAt,
              :startDate, :endDate, 'Asia/Seoul'
            )
            """)
        .param("eventId", eventId)
        .param("ownerId", ownerId)
        .param("scheduleKind", scheduleKind)
        .param("startAt", startAt == null ? null : Timestamp.from(startAt))
        .param("endAt", endAt == null ? null : Timestamp.from(endAt))
        .param("startDate", startDate == null ? null : java.sql.Date.valueOf(startDate))
        .param("endDate", endDateExclusive == null ? null : java.sql.Date.valueOf(endDateExclusive))
        .update();
    return new Seed(eventId, memoId, applicationId);
  }

  record Seed(UUID eventId, UUID memoId, UUID applicationId) {}
}
