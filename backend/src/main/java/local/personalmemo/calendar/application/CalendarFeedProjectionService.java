package local.personalmemo.calendar.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarFeedProjectionService {
  private final JdbcClient db;

  public CalendarFeedProjectionService(JdbcClient db) {
    this.db = db;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void cancelForMemo(UUID ownerId, UUID memoId, Instant changedAt) {
    cancel(
        """
        select item.id
          from memo_items item
         where item.memo_id = :sourceId
           and item.owner_id = :ownerId
        """,
        ownerId,
        memoId,
        changedAt);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void cancelForApplication(UUID ownerId, UUID applicationId, Instant changedAt) {
    cancel(
        """
        select item.id
          from memo_items item
         where item.application_id = :sourceId
           and item.owner_id = :ownerId
        """,
        ownerId,
        applicationId,
        changedAt);
  }

  private void cancel(String sourceQuery, UUID ownerId, UUID sourceId, Instant changedAt) {
    Timestamp now = Timestamp.from(changedAt);
    List<UUID> lockedFeedIds =
        db.sql(
                """
                select feed.id
                  from calendar_feeds feed
                 where feed.owner_id = :ownerId
                   and exists (
                     select 1
                       from calendar_feed_entries entry
                      where entry.feed_id = feed.id
                        and entry.owner_id = feed.owner_id
                        and entry.state = 'ACTIVE'
                        and entry.active_memo_item_id in (
                """
                    + sourceQuery
                    + """
                     )
                   )
                 order by feed.id
                 for update
                """)
            .param("ownerId", ownerId)
            .param("sourceId", sourceId)
            .query((resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
            .list();
    if (lockedFeedIds.isEmpty()) {
      return;
    }
    List<UUID> changedFeedIds =
        db
            .sql(
                """
                update calendar_feed_entries entry
                   set active_memo_item_id = null,
                       active_owner_id = null,
                       active_item_kind = null,
                       state = 'CANCELLED',
                       sequence = sequence + 1,
                       updated_at = :now,
                       cancelled_at = :now
                 where entry.owner_id = :ownerId
                   and entry.feed_id in (:feedIds)
                   and entry.state = 'ACTIVE'
                   and entry.active_memo_item_id in (
                """
                    + sourceQuery
                    + """
                   )
                returning entry.feed_id
                """)
            .param("now", now)
            .param("ownerId", ownerId)
            .param("sourceId", sourceId)
            .param("feedIds", lockedFeedIds)
            .query((resultSet, rowNumber) -> resultSet.getObject("feed_id", UUID.class))
            .list()
            .stream()
            .distinct()
            .toList();
    if (changedFeedIds.isEmpty()) {
      return;
    }
    db.sql(
            """
            update calendar_feeds feed
               set version = version + 1,
                   updated_at = :now
             where feed.owner_id = :ownerId
               and feed.id in (:feedIds)
            """)
        .param("now", now)
        .param("ownerId", ownerId)
        .param("feedIds", changedFeedIds)
        .update();
  }
}
