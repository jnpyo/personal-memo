package local.personalmemo.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.calendar.CalendarFeedTestData.Seed;
import local.personalmemo.calendar.domain.CalendarFeedSecret;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class CalendarFeedCapacityIntegrationTest extends PostgresIntegrationTestSupport {

  @BeforeEach
  void activateFixtureOwner() {
    db.sql(
            """
            update users
               set primary_email = 'calendar-capacity-owner@example.test',
                   primary_email_normalized = 'calendar-capacity-owner@example.test',
                   display_name = 'Calendar Capacity Owner',
                   status = 'ACTIVE'
             where id = :ownerId
            """)
        .param("ownerId", OWNER_ID)
        .update();
  }

  @Test
  void ownerLimitCountsRevokedFeedsAndRejectedCreateWritesNothing() throws Exception {
    Seed event =
        CalendarFeedTestData.timed(
            db,
            OWNER_ID,
            "수명 피드 상한 fixture",
            Instant.parse("2026-10-01T09:00:00Z"),
            Instant.parse("2026-10-01T10:00:00Z"));
    seedRevokedFeeds(99);
    assertThat(feedCount()).isEqualTo(99);
    assertThat(revokedFeedCount()).isEqualTo(99);

    MvcResult hundredthResult =
        createFeed("capacity-feed-100", "100번째 피드", List.of(event.eventId()), secret(100));
    assertThat(hundredthResult.getResponse().getStatus()).isEqualTo(201);
    JsonNode hundredth = response(hundredthResult);
    UUID hundredthId = UUID.fromString(hundredth.path("id").asText());
    assertThat(feedCount()).isEqualTo(100);

    MvcResult revokedResult =
        mvc.perform(
                post("/api/v1/calendar-feeds/{id}/revoke", hundredthId)
                    .header("Idempotency-Key", "capacity-feed-100-revoke")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("expectedVersion", 1))))
            .andReturn();
    assertThat(revokedResult.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(revokedResult).path("status").asText()).isEqualTo("REVOKED");
    assertThat(revokedFeedCount()).isEqualTo(100);

    String rejectedKey = "capacity-feed-101-rejected";
    String rejectedSecret = secret(101);
    MvcResult rejected =
        createFeed(rejectedKey, "거부되어야 하는 101번째 피드", List.of(event.eventId()), rejectedSecret);

    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(rejected).path("code").asText()).isEqualTo("CALENDAR_FEED_LIMIT_EXCEEDED");
    assertThat(feedCount()).isEqualTo(100);
    assertThat(revokedFeedCount()).isEqualTo(100);
    assertThat(db.sql("select count(*) from calendar_feed_entries").query(Long.class).single())
        .isEqualTo(1);
    assertThat(
            db.sql("select count(*) from calendar_feeds where token_verifier = :verifier")
                .param("verifier", CalendarFeedSecret.requireVerifier(rejectedSecret))
                .query(Long.class)
                .single())
        .isZero();
    assertNoIdempotencyRecord("CALENDAR_FEED_CREATE", rejectedKey);
  }

  @Test
  void entryLimitCountsCancelledTombstonesAndRejectedAddWritesNothing() throws Exception {
    List<Seed> events = new ArrayList<>();
    Instant firstStart = Instant.parse("2026-10-02T00:00:00Z");
    for (int index = 0; index < 101; index++) {
      Instant start = firstStart.plus(Duration.ofMinutes(index));
      events.add(
          CalendarFeedTestData.timed(
              db, OWNER_ID, "항목 상한 fixture " + index, start, start.plus(Duration.ofMinutes(30))));
    }

    List<UUID> firstHundred = events.subList(0, 100).stream().map(Seed::eventId).toList();
    MvcResult createdResult =
        createFeed("capacity-entry-create", "항목 100개 피드", firstHundred, secret(102));
    assertThat(createdResult.getResponse().getStatus()).isEqualTo(201);
    JsonNode created = response(createdResult);
    UUID feedId = UUID.fromString(created.path("id").asText());
    UUID removedEntryId = UUID.fromString(created.at("/entries/0/id").asText());
    assertThat(created.path("eventCount").asInt()).isEqualTo(100);
    assertThat(created.path("entries")).hasSize(100);
    assertThat(entryCount(feedId)).isEqualTo(100);

    MvcResult removedResult =
        mvc.perform(
                post("/api/v1/calendar-feeds/{id}/events/{entryId}/remove", feedId, removedEntryId)
                    .header("Idempotency-Key", "capacity-entry-remove")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("expectedVersion", 1))))
            .andReturn();
    assertThat(removedResult.getResponse().getStatus()).isEqualTo(200);
    JsonNode removed = response(removedResult);
    assertThat(removed.path("version").asLong()).isEqualTo(2);
    assertThat(removed.path("eventCount").asInt()).isEqualTo(99);
    assertThat(entryCount(feedId)).isEqualTo(100);
    assertThat(cancelledEntryCount(feedId)).isEqualTo(1);

    UUID rejectedEventId = events.get(100).eventId();
    String rejectedKey = "capacity-entry-101-rejected";
    FeedSnapshot beforeRejection = feedSnapshot(feedId);
    MvcResult rejected =
        mvc.perform(
                post("/api/v1/calendar-feeds/{id}/events", feedId)
                    .header("Idempotency-Key", rejectedKey)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of("eventId", rejectedEventId, "expectedVersion", 2))))
            .andReturn();

    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(rejected).path("code").asText())
        .isEqualTo("CALENDAR_FEED_ENTRY_LIMIT_EXCEEDED");
    assertThat(feedSnapshot(feedId)).isEqualTo(beforeRejection);
    assertThat(entryCount(feedId)).isEqualTo(100);
    assertThat(cancelledEntryCount(feedId)).isEqualTo(1);
    assertThat(
            db.sql(
                    """
                    select count(*)
                      from calendar_feed_entries
                     where feed_id = :feedId
                       and active_memo_item_id = :eventId
                    """)
                .param("feedId", feedId)
                .param("eventId", rejectedEventId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql(
                    """
                    select count(*)
                      from event_details event
                      join memo_items item
                        on item.id = event.memo_item_id
                       and item.owner_id = event.owner_id
                       and item.kind = event.item_kind
                      join analysis_applications application
                        on application.id = item.application_id
                       and application.owner_id = item.owner_id
                     where event.memo_item_id = :eventId
                       and event.owner_id = :ownerId
                       and application.status = 'APPLIED'
                    """)
                .param("eventId", rejectedEventId)
                .param("ownerId", OWNER_ID)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertNoIdempotencyRecord("CALENDAR_FEED_EVENT_ADD", rejectedKey);
  }

  private MvcResult createFeed(String key, String displayName, List<UUID> eventIds, String secret)
      throws Exception {
    return mvc.perform(
            post("/api/v1/calendar-feeds")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "displayName",
                            displayName,
                            "disclosureMode",
                            "BUSY_ONLY",
                            "eventIds",
                            eventIds,
                            "bearerSecret",
                            secret))))
        .andReturn();
  }

  private void seedRevokedFeeds(int count) {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-25T04:00:00Z"));
    db.sql(
            """
            insert into calendar_feeds(
              id, owner_id, display_name, disclosure_mode, status, version, token_verifier,
              created_at, updated_at, rotated_at, revoked_at
            )
            select md5('calendar-feed-capacity-' || ordinal)::uuid,
                   :ownerId,
                   'retained revoked feed ' || ordinal,
                   'BUSY_ONLY',
                   'REVOKED',
                   1,
                   lpad(to_hex(ordinal), 64, '0'),
                   :now,
                   :now,
                   :now,
                   :now
              from generate_series(1, :count) ordinal
            """)
        .param("ownerId", OWNER_ID)
        .param("now", now)
        .param("count", count)
        .update();
  }

  private long feedCount() {
    return db.sql("select count(*) from calendar_feeds where owner_id = :ownerId")
        .param("ownerId", OWNER_ID)
        .query(Long.class)
        .single();
  }

  private long revokedFeedCount() {
    return db.sql(
            """
            select count(*)
              from calendar_feeds
             where owner_id = :ownerId
               and status = 'REVOKED'
            """)
        .param("ownerId", OWNER_ID)
        .query(Long.class)
        .single();
  }

  private long entryCount(UUID feedId) {
    return db.sql(
            "select count(*) from calendar_feed_entries where feed_id = :feedId and owner_id ="
                + " :ownerId")
        .param("feedId", feedId)
        .param("ownerId", OWNER_ID)
        .query(Long.class)
        .single();
  }

  private long cancelledEntryCount(UUID feedId) {
    return db.sql(
            """
            select count(*)
              from calendar_feed_entries
             where feed_id = :feedId
               and owner_id = :ownerId
               and state = 'CANCELLED'
            """)
        .param("feedId", feedId)
        .param("ownerId", OWNER_ID)
        .query(Long.class)
        .single();
  }

  private FeedSnapshot feedSnapshot(UUID feedId) {
    return db.sql(
            """
            select version, updated_at
              from calendar_feeds
             where id = :feedId
               and owner_id = :ownerId
            """)
        .param("feedId", feedId)
        .param("ownerId", OWNER_ID)
        .query(
            (resultSet, rowNumber) ->
                new FeedSnapshot(
                    resultSet.getLong("version"), resultSet.getTimestamp("updated_at").toInstant()))
        .single();
  }

  private void assertNoIdempotencyRecord(String operation, String key) {
    assertThat(
            db.sql(
                    """
                    select count(*)
                      from idempotency_records
                     where owner_id = :ownerId
                       and operation = :operation
                       and idempotency_key = :key
                    """)
                .param("ownerId", OWNER_ID)
                .param("operation", operation)
                .param("key", key)
                .query(Long.class)
                .single())
        .isZero();
  }

  private String secret(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private record FeedSnapshot(long version, Instant updatedAt) {}
}
