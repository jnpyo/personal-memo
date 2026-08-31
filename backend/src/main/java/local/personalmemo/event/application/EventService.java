package local.personalmemo.event.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.event.api.EventDtos.View;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
  private static final int MAX_LIMIT = 100;
  private static final int EXPORT_PROBE_LIMIT = MAX_LIMIT + 1;

  private final JdbcClient db;
  private final CurrentIdentity identity;
  private final IcalendarSerializer serializer;

  public EventService(JdbcClient db, CurrentIdentity identity, IcalendarSerializer serializer) {
    this.db = db;
    this.identity = identity;
    this.serializer = serializer;
  }

  @Transactional(readOnly = true)
  public List<View> list(int requestedLimit) {
    int limit = validateLimit(requestedLimit);
    return findEligible(limit).stream().map(this::toView).toList();
  }

  @Transactional(readOnly = true)
  public byte[] exportCalendar() {
    List<CanonicalScheduledEvent> events = findEligible(EXPORT_PROBE_LIMIT);
    if (events.size() > MAX_LIMIT) {
      throw DomainException.invalid(
          "ICALENDAR_EVENT_LIMIT_EXCEEDED",
          "The calendar export contains more than 100 eligible events.");
    }
    return events.isEmpty() ? new byte[0] : serializer.serialize(identity.ownerId(), events);
  }

  private List<CanonicalScheduledEvent> findEligible(int limit) {
    return db.sql(
            """
            select selected.id,
                   selected.title,
                   selected.schedule_kind,
                   selected.start_at_utc,
                   selected.end_at_utc,
                   selected.start_local_date,
                   selected.end_local_date_exclusive,
                   selected.source_time_zone,
                   selected.event_created_at
              from (
                select item.id,
                       item.title,
                       event.schedule_kind,
                       event.start_at_utc,
                       event.end_at_utc,
                       event.start_local_date,
                       event.end_local_date_exclusive,
                       event.source_time_zone,
                       item.created_at as event_created_at,
                       application.applied_at
                  from event_details event
                  join memo_items item
                    on item.id = event.memo_item_id
                   and item.owner_id = event.owner_id
                   and item.kind = event.item_kind
                  join memos memo
                    on memo.id = item.memo_id
                   and memo.owner_id = item.owner_id
                  join analysis_applications application
                    on application.id = item.application_id
                   and application.owner_id = item.owner_id
                   and application.memo_id = item.memo_id
                   and application.memo_revision = item.memo_revision
                 where event.owner_id = :ownerId
                   and event.item_kind = 'EVENT'
                   and item.kind = 'EVENT'
                   and item.archived_at is null
                   and memo.status = 'ACTIVE'
                   and item.memo_revision = memo.current_revision
                   and application.status = 'APPLIED'
                 order by application.applied_at desc, item.id
                 limit :limit
              ) selected
             order by coalesce(
                        selected.start_at_utc,
                        selected.start_local_date::timestamp at time zone selected.source_time_zone
                      ),
                      selected.id
            """)
        .param("ownerId", identity.ownerId())
        .param("limit", limit)
        .query(this::mapEvent)
        .list();
  }

  private int validateLimit(int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw DomainException.invalid("INVALID_EVENT_LIMIT", "limit must be between 1 and 100.");
    }
    return limit;
  }

  private CanonicalScheduledEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
    Timestamp startAt = resultSet.getTimestamp("start_at_utc");
    Timestamp endAt = resultSet.getTimestamp("end_at_utc");
    java.sql.Date startDate = resultSet.getDate("start_local_date");
    java.sql.Date endDate = resultSet.getDate("end_local_date_exclusive");
    return new CanonicalScheduledEvent(
        resultSet.getObject("id", java.util.UUID.class),
        resultSet.getString("title"),
        resultSet.getString("schedule_kind"),
        startAt == null ? null : startAt.toInstant(),
        endAt == null ? null : endAt.toInstant(),
        startDate == null ? null : startDate.toLocalDate(),
        endDate == null ? null : endDate.toLocalDate(),
        resultSet.getString("source_time_zone"),
        resultSet.getTimestamp("event_created_at").toInstant());
  }

  private View toView(CanonicalScheduledEvent event) {
    return new View(
        event.id(),
        event.title(),
        event.scheduleKind(),
        event.startAt(),
        event.endAt(),
        event.startDate(),
        event.endDateExclusive(),
        event.sourceTimeZone());
  }
}
