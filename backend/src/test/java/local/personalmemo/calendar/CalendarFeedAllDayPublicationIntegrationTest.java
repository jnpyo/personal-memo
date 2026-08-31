package local.personalmemo.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.calendar.CalendarFeedTestData.Seed;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class CalendarFeedAllDayPublicationIntegrationTest extends PostgresIntegrationTestSupport {

  @BeforeEach
  void activateFixtureOwner() {
    db.sql(
            """
            update users
               set primary_email = 'calendar-all-day-owner@example.test',
                   primary_email_normalized = 'calendar-all-day-owner@example.test',
                   display_name = 'Calendar All Day Owner',
                   status = 'ACTIVE'
             where id = :ownerId
            """)
        .param("ownerId", OWNER_ID)
        .update();
  }

  @Test
  void publishesActiveAndCancelledAllDayEntriesWithoutMutatingCanonicalOrProjectionState()
      throws Exception {
    Seed event =
        CalendarFeedTestData.allDay(
            db,
            OWNER_ID,
            "비공개 종일 일정 제목",
            LocalDate.parse("2026-09-10"),
            LocalDate.parse("2026-09-13"));
    String token = secret(41);
    JsonNode created =
        response(
            mvc.perform(
                    post("/api/v1/calendar-feeds")
                        .header("Idempotency-Key", "all-day-feed-create")
                        .contentType("application/json")
                        .content(
                            json.writeValueAsBytes(
                                Map.of(
                                    "displayName",
                                    "종일 일정 수신자",
                                    "disclosureMode",
                                    "BUSY_ONLY",
                                    "eventIds",
                                    List.of(event.eventId()),
                                    "bearerSecret",
                                    token))))
                .andReturn());
    UUID feedId = UUID.fromString(created.path("id").asText());
    UUID entryId = UUID.fromString(created.at("/entries/0/id").asText());
    String publicUid = publicUid(feedId);

    PublicationSnapshot activeBefore = publicationSnapshot(feedId);
    MvcResult activeGet = publicGet(token);
    assertThat(activeGet.getResponse().getStatus()).isEqualTo(200);
    assertThat(unfold(calendar(activeGet)))
        .contains(
            "UID:" + publicUid,
            "SEQUENCE:0",
            "DTSTART;VALUE=DATE:20260910",
            "DTEND;VALUE=DATE:20260913",
            "SUMMARY:Busy")
        .doesNotContain("비공개 종일 일정 제목", "STATUS:CANCELLED");
    assertHeadMatchesGet(token, activeGet);
    assertThat(publicationSnapshot(feedId)).isEqualTo(activeBefore);

    JsonNode cancelled =
        response(
            mvc.perform(
                    post("/api/v1/calendar-feeds/{id}/events/{entryId}/remove", feedId, entryId)
                        .header("Idempotency-Key", "all-day-feed-cancel")
                        .contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("expectedVersion", 1))))
                .andReturn());
    assertThat(cancelled.at("/entries/0/state").asText()).isEqualTo("CANCELLED");
    assertThat(cancelled.at("/entries/0/sequence").asInt()).isEqualTo(1);

    PublicationSnapshot cancelledBefore = publicationSnapshot(feedId);
    MvcResult cancelledGet = publicGet(token);
    assertThat(cancelledGet.getResponse().getStatus()).isEqualTo(200);
    assertThat(unfold(calendar(cancelledGet)))
        .contains(
            "UID:" + publicUid,
            "SEQUENCE:1",
            "DTSTART;VALUE=DATE:20260910",
            "DTEND;VALUE=DATE:20260913",
            "STATUS:CANCELLED")
        .doesNotContain("SUMMARY:", "비공개 종일 일정 제목");
    assertHeadMatchesGet(token, cancelledGet);
    assertThat(publicationSnapshot(feedId)).isEqualTo(cancelledBefore);
  }

  private MvcResult publicGet(String token) throws Exception {
    return mvc.perform(get("/calendar/v1/feed.ics").queryParam("token", token)).andReturn();
  }

  private void assertHeadMatchesGet(String token, MvcResult getResult) throws Exception {
    MvcResult headResult =
        mvc.perform(head("/calendar/v1/feed.ics").queryParam("token", token)).andReturn();
    assertThat(headResult.getResponse().getStatus()).isEqualTo(getResult.getResponse().getStatus());
    assertThat(headResult.getResponse().getContentType())
        .isEqualTo(getResult.getResponse().getContentType());
    assertThat(headResult.getResponse().getHeader("Content-Length"))
        .isEqualTo(getResult.getResponse().getHeader("Content-Length"));
    assertThat(headResult.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(headResult.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(headResult.getResponse().getContentAsByteArray()).isEmpty();
  }

  private String publicUid(UUID feedId) {
    return db.sql("select public_uid from calendar_feed_entries where feed_id = :feedId")
        .param("feedId", feedId)
        .query(String.class)
        .single();
  }

  private PublicationSnapshot publicationSnapshot(UUID feedId) {
    return db.sql(
            """
            select feed.version,
                   feed.updated_at as feed_updated_at,
                   entry.state,
                   entry.sequence,
                   entry.updated_at as entry_updated_at,
                   (select count(*) from memos) as memo_count,
                   (select count(*) from memo_revisions) as revision_count,
                   (select count(*) from analysis_applications) as application_count,
                   (select count(*) from memo_items) as item_count,
                   (select count(*) from event_details) as event_count,
                   (select count(*) from idempotency_records) as idempotency_count
              from calendar_feeds feed
              join calendar_feed_entries entry
                on entry.feed_id = feed.id
               and entry.owner_id = feed.owner_id
             where feed.id = :feedId
               and feed.owner_id = :ownerId
            """)
        .param("feedId", feedId)
        .param("ownerId", OWNER_ID)
        .query(
            (resultSet, rowNumber) ->
                new PublicationSnapshot(
                    resultSet.getLong("version"),
                    resultSet.getTimestamp("feed_updated_at").toInstant(),
                    resultSet.getString("state"),
                    resultSet.getInt("sequence"),
                    resultSet.getTimestamp("entry_updated_at").toInstant(),
                    resultSet.getLong("memo_count"),
                    resultSet.getLong("revision_count"),
                    resultSet.getLong("application_count"),
                    resultSet.getLong("item_count"),
                    resultSet.getLong("event_count"),
                    resultSet.getLong("idempotency_count")))
        .single();
  }

  private String calendar(MvcResult result) {
    return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private String unfold(String value) {
    return value.replace("\r\n ", "");
  }

  private String secret(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private record PublicationSnapshot(
      long version,
      Instant feedUpdatedAt,
      String entryState,
      int entrySequence,
      Instant entryUpdatedAt,
      long memoCount,
      long revisionCount,
      long applicationCount,
      long itemCount,
      long eventCount,
      long idempotencyCount) {}
}
