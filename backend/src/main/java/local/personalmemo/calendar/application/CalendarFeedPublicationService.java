package local.personalmemo.calendar.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.calendar.domain.CalendarFeedSecret;
import local.personalmemo.common.error.DomainException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarFeedPublicationService {
  private static final int MAX_ENTRIES = 100;
  private static final int PROBE_LIMIT = MAX_ENTRIES + 1;

  private final JdbcClient db;
  private final RecipientIcalendarSerializer serializer;
  private final CalendarFeedPublicationProperties publication;

  public CalendarFeedPublicationService(
      JdbcClient db,
      RecipientIcalendarSerializer serializer,
      CalendarFeedPublicationProperties publication) {
    this.db = db;
    this.serializer = serializer;
    this.publication = publication;
  }

  @Transactional(readOnly = true)
  public Optional<byte[]> read(String secret) {
    String verifier = CalendarFeedSecret.lookupVerifier(secret);
    Optional<PublishedFeed> feed =
        db.sql(
                """
                select feed.id, feed.owner_id, feed.disclosure_mode
                  from calendar_feeds feed
                  join users owner on owner.id = feed.owner_id
                 where feed.token_verifier = :verifier
                   and feed.status = 'ACTIVE'
                   and owner.status = 'ACTIVE'
                   and feed.publication_scope = :publicationScope
                   and (
                     (
                       :publicMode = false
                       and feed.public_consent_policy_version is null
                       and feed.public_consent_granted_at is null
                     )
                     or
                     (
                       :publicMode = true
                       and feed.public_consent_policy_version = :consentPolicyVersion
                       and feed.public_consent_granted_at is not null
                     )
                   )
                """)
            .param("verifier", verifier)
            .param("publicationScope", publication.enabled() ? "PUBLIC_HTTPS" : "LOCAL_ONLY")
            .param("publicMode", publication.enabled())
            .param("consentPolicyVersion", publication.consentPolicyVersion())
            .query(
                (resultSet, rowNumber) ->
                    new PublishedFeed(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("owner_id", UUID.class),
                        resultSet.getString("disclosure_mode")))
            .optional();
    if (feed.isEmpty()) {
      return Optional.empty();
    }
    List<PublishableEntry> entries = findPublishable(feed.get().id(), feed.get().ownerId());
    if (entries.size() > MAX_ENTRIES) {
      throw DomainException.invalid(
          "CALENDAR_FEED_ENTRY_LIMIT_EXCEEDED",
          "The calendar feed contains more than 100 retained entries.");
    }
    if (entries.stream()
        .anyMatch(entry -> "ACTIVE".equals(entry.event().state()) && !entry.currentlyEligible())) {
      throw DomainException.conflict(
          "CALENDAR_FEED_PROJECTION_INTEGRITY_CONFLICT",
          "The calendar feed projection no longer matches its source event.");
    }
    List<RecipientCalendarEvent> events = entries.stream().map(PublishableEntry::event).toList();
    if (events.isEmpty()) {
      return Optional.of(new byte[0]);
    }
    return Optional.of(serializer.serialize(feed.get().disclosureMode(), events));
  }

  private List<PublishableEntry> findPublishable(UUID feedId, UUID ownerId) {
    return db.sql(
            """
            select entry.public_uid,
                   case when entry.state = 'ACTIVE' then item.title else null end as title,
                   entry.state,
                   entry.sequence,
                   entry.schedule_kind,
                   entry.start_at_utc,
                   entry.end_at_utc,
                   entry.start_local_date,
                   entry.end_local_date_exclusive,
                   entry.source_time_zone,
                   entry.updated_at,
                   (
                     entry.state = 'ACTIVE'
                     and event.memo_item_id is not null
                     and item.archived_at is null
                     and memo.status = 'ACTIVE'
                     and item.memo_revision = memo.current_revision
                     and application.status = 'APPLIED'
                   ) as current_eligible
              from calendar_feed_entries entry
              left join memo_items item
                on item.id = entry.active_memo_item_id
               and item.owner_id = entry.active_owner_id
               and item.kind = entry.active_item_kind
              left join event_details event
                on event.memo_item_id = item.id
               and event.owner_id = item.owner_id
               and event.item_kind = item.kind
              left join memos memo
                on memo.id = item.memo_id
               and memo.owner_id = item.owner_id
              left join analysis_applications application
                on application.id = item.application_id
               and application.owner_id = item.owner_id
               and application.memo_id = item.memo_id
               and application.memo_revision = item.memo_revision
             where entry.feed_id = :feedId
               and entry.owner_id = :ownerId
             order by entry.created_at, entry.id
             limit :limit
            """)
        .param("feedId", feedId)
        .param("ownerId", ownerId)
        .param("limit", PROBE_LIMIT)
        .query(
            (resultSet, rowNumber) ->
                new PublishableEntry(
                    mapEvent(resultSet, rowNumber), resultSet.getBoolean("current_eligible")))
        .list();
  }

  private RecipientCalendarEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
    return new RecipientCalendarEvent(
        resultSet.getString("public_uid"),
        resultSet.getString("title"),
        resultSet.getString("state"),
        resultSet.getInt("sequence"),
        resultSet.getString("schedule_kind"),
        instant(resultSet.getTimestamp("start_at_utc")),
        instant(resultSet.getTimestamp("end_at_utc")),
        localDate(resultSet.getDate("start_local_date")),
        localDate(resultSet.getDate("end_local_date_exclusive")),
        resultSet.getString("source_time_zone"),
        instant(resultSet.getTimestamp("updated_at")));
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private LocalDate localDate(java.sql.Date value) {
    return value == null ? null : value.toLocalDate();
  }

  private record PublishedFeed(UUID id, UUID ownerId, String disclosureMode) {}

  private record PublishableEntry(RecipientCalendarEvent event, boolean currentlyEligible) {}
}
