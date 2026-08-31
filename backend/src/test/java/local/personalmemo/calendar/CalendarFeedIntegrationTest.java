package local.personalmemo.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import local.personalmemo.calendar.CalendarFeedTestData.Seed;
import local.personalmemo.calendar.api.CalendarFeedDtos.AddEvent;
import local.personalmemo.calendar.application.CalendarFeedManagementService;
import local.personalmemo.calendar.application.CalendarFeedProjectionService;
import local.personalmemo.calendar.domain.CalendarFeedSecret;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class CalendarFeedIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired private CalendarFeedManagementService management;
  @Autowired private CalendarFeedProjectionService projection;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void activateFixtureOwner() {
    db.sql(
            """
            update users
               set primary_email = 'calendar-owner@example.test',
                   primary_email_normalized = 'calendar-owner@example.test',
                   display_name = 'Calendar Owner',
                   status = 'ACTIVE'
             where id = :ownerId
            """)
        .param("ownerId", OWNER_ID)
        .update();
  }

  @Test
  void managesOnlyEligibleOwnerEventsAndNeverReturnsOrStoresTheBearerSecret() throws Exception {
    Seed event =
        CalendarFeedTestData.timed(
            db,
            OWNER_ID,
            "디스코드 접속",
            Instant.parse("2026-09-01T09:00:00Z"),
            Instant.parse("2026-09-01T10:00:00Z"));
    String secret = secret(1);

    MvcResult eligible = mvc.perform(get("/api/v1/calendar-feeds/eligible-events")).andReturn();
    assertThat(eligible.getResponse().getStatus()).isEqualTo(200);
    assertThat(eligible.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response(eligible).path("truncated").asBoolean()).isFalse();
    assertThat(response(eligible).path("items")).hasSize(1);
    assertThat(response(eligible).at("/items/0/id").asText()).isEqualTo(event.eventId().toString());

    MvcResult created =
        createFeed("feed-create-safe", "공유 일정", "TITLE", List.of(event.eventId()), secret);
    MvcResult replay =
        createFeed("feed-create-safe", "공유 일정", "TITLE", List.of(event.eventId()), secret);
    JsonNode detail = response(created);

    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertThat(replay.getResponse().getStatus()).isEqualTo(201);
    assertThat(response(replay)).isEqualTo(detail);
    assertThat(detail.path("eventCount").asInt()).isEqualTo(1);
    assertThat(detail.path("entries")).hasSize(1);
    assertThat(detail.at("/entries/0/eventId").asText()).isEqualTo(event.eventId().toString());
    assertThat(detail.toString())
        .doesNotContain(secret, "bearerSecret", "feedUrl", "publicUrl", "token");
    UUID feedId = UUID.fromString(detail.path("id").asText());

    assertThat(
            db.sql("select token_verifier from calendar_feeds where id = :id")
                .param("id", feedId)
                .query(String.class)
                .single())
        .isEqualTo(CalendarFeedSecret.requireVerifier(secret));
    String storedResponse =
        db.sql(
                """
                select response_json::text
                  from idempotency_records
                 where owner_id = :ownerId
                   and operation = 'CALENDAR_FEED_CREATE'
                   and idempotency_key = 'feed-create-safe'
                """)
            .param("ownerId", OWNER_ID)
            .query(String.class)
            .single();
    assertThat(storedResponse).doesNotContain(secret, "bearerSecret", "feedUrl", "token");

    JsonNode list = response(mvc.perform(get("/api/v1/calendar-feeds")).andReturn());
    assertThat(list).hasSize(1);
    assertThat(list.at("/0/eventCount").asInt()).isEqualTo(1);

    db.sql("update analysis_applications set status = 'UNDONE', undone_at = :now where id = :id")
        .param("now", Timestamp.from(Instant.parse("2026-08-25T02:00:00Z")))
        .param("id", event.applicationId())
        .update();
    MvcResult noLongerEligible =
        mvc.perform(get("/api/v1/calendar-feeds/{id}", feedId)).andReturn();
    assertThat(noLongerEligible.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(noLongerEligible).path("code").asText())
        .isEqualTo("CALENDAR_FEED_PROJECTION_INTEGRITY_CONFLICT");
    assertThat(
            response(mvc.perform(get("/api/v1/calendar-feeds")).andReturn())
                .at("/0/eventCount")
                .asInt())
        .isZero();
    assertGenericNotFound(publicFeed(secret));
  }

  @Test
  void publishesCurrentTitleOrBusyAndMakesEveryInvalidTokenStateAnEmptyGeneric404()
      throws Exception {
    Seed event =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "비공개 원제목", Instant.parse("2026-09-02T09:00:00Z"), null);
    String firstSecret = secret(2);
    String replacementSecret = secret(3);
    JsonNode created =
        response(
            createFeed(
                "feed-public-create", "수신자 A", "TITLE", List.of(event.eventId()), firstSecret));
    UUID feedId = UUID.fromString(created.path("id").asText());

    ReadSnapshot beforePublicReads = readSnapshot(feedId);
    MvcResult titleResponse = publicFeed(firstSecret);
    assertThat(titleResponse.getResponse().getStatus()).isEqualTo(200);
    assertThat(titleResponse.getResponse().getContentType())
        .isEqualTo("text/calendar;charset=UTF-8");
    assertThat(calendar(titleResponse)).contains("SUMMARY:비공개 원제목\r\n", "SEQUENCE:0\r\n");
    MvcResult headResponse =
        mvc.perform(head("/calendar/v1/feed.ics").queryParam("token", firstSecret)).andReturn();
    assertThat(headResponse.getResponse().getStatus())
        .isEqualTo(titleResponse.getResponse().getStatus());
    assertThat(headResponse.getResponse().getContentType())
        .isEqualTo(titleResponse.getResponse().getContentType());
    assertThat(headResponse.getResponse().getHeader("Content-Length"))
        .isEqualTo(titleResponse.getResponse().getHeader("Content-Length"));
    assertThat(headResponse.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(headResponse.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(headResponse.getResponse().getContentAsByteArray()).isEmpty();
    assertThat(readSnapshot(feedId)).isEqualTo(beforePublicReads);

    ReadSnapshot beforeUnknownRead = readSnapshot(feedId);
    assertGenericNotFound(publicFeed(secret(99)));
    assertThat(readSnapshot(feedId)).isEqualTo(beforeUnknownRead);

    db.sql("update memo_items set title = '현재 제목' where id = :id and owner_id = :ownerId")
        .param("id", event.eventId())
        .param("ownerId", OWNER_ID)
        .update();
    assertThat(calendar(publicFeed(firstSecret))).contains("SUMMARY:현재 제목\r\n");

    JsonNode busy =
        response(
            mvc.perform(
                    patch("/api/v1/calendar-feeds/{id}", feedId)
                        .header("Idempotency-Key", "feed-busy-update")
                        .contentType("application/json")
                        .content(
                            json.writeValueAsBytes(
                                Map.of(
                                    "displayName",
                                    "수신자 A",
                                    "disclosureMode",
                                    "BUSY_ONLY",
                                    "expectedVersion",
                                    1))))
                .andReturn());
    assertThat(busy.path("version").asLong()).isEqualTo(2);
    assertThat(busy.at("/entries/0/sequence").asInt()).isEqualTo(1);
    assertThat(calendar(publicFeed(firstSecret)))
        .contains("SUMMARY:Busy\r\n", "SEQUENCE:1\r\n")
        .doesNotContain("현재 제목");

    JsonNode rotated =
        response(
            mvc.perform(
                    post("/api/v1/calendar-feeds/{id}/rotate", feedId)
                        .header("Idempotency-Key", "feed-secret-rotate")
                        .contentType("application/json")
                        .content(
                            json.writeValueAsBytes(
                                Map.of("bearerSecret", replacementSecret, "expectedVersion", 2))))
                .andReturn());
    assertThat(rotated.path("version").asLong()).isEqualTo(3);
    assertGenericNotFound(publicFeed(firstSecret));
    assertThat(publicFeed(replacementSecret).getResponse().getStatus()).isEqualTo(200);

    db.sql(
            "update calendar_feed_entries set start_at_utc = start_at_utc + interval '0.5 second' where feed_id = :id")
        .param("id", feedId)
        .update();
    assertGenericNotFound(publicFeed(replacementSecret));
    db.sql(
            "update calendar_feed_entries set start_at_utc = date_trunc('second', start_at_utc) where feed_id = :id")
        .param("id", feedId)
        .update();

    db.sql("update users set status = 'DISABLED' where id = :ownerId")
        .param("ownerId", OWNER_ID)
        .update();
    assertGenericNotFound(publicFeed(replacementSecret));
    db.sql("update users set status = 'ACTIVE' where id = :ownerId")
        .param("ownerId", OWNER_ID)
        .update();

    assertGenericNotFound(publicFeed("malformed"));
    assertGenericNotFound(
        mvc.perform(
                get("/calendar/v1/feed.ics")
                    .queryParam("token", replacementSecret, replacementSecret))
            .andReturn());
    assertGenericNotFound(
        mvc.perform(
                get("/calendar/v1/feed.ics")
                    .queryParam("token", replacementSecret)
                    .queryParam("extra", "1"))
            .andReturn());

    JsonNode revoked =
        response(
            mvc.perform(
                    post("/api/v1/calendar-feeds/{id}/revoke", feedId)
                        .header("Idempotency-Key", "feed-revoke")
                        .contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("expectedVersion", 3))))
                .andReturn());
    assertThat(revoked.path("status").asText()).isEqualTo("REVOKED");
    assertThat(revoked.path("version").asLong()).isEqualTo(4);
    assertGenericNotFound(publicFeed(replacementSecret));
  }

  @Test
  void rejectsTheWholePublicFeedInsteadOfPartiallyPublishingAroundAnIneligibleActiveEntry()
      throws Exception {
    Seed healthy =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "노출되면 안 되는 정상 항목", Instant.parse("2026-09-02T11:00:00Z"), null);
    Seed corrupted =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "직접 손상된 항목", Instant.parse("2026-09-02T12:00:00Z"), null);
    String token = secret(33);
    JsonNode created =
        response(
            createFeed(
                "feed-no-partial-create",
                "부분 노출 금지",
                "TITLE",
                List.of(healthy.eventId(), corrupted.eventId()),
                token));
    assertThat(calendar(publicFeed(token))).contains("노출되면 안 되는 정상 항목", "직접 손상된 항목");

    db.sql("update analysis_applications set status = 'UNDONE', undone_at = :now where id = :id")
        .param("now", Timestamp.from(Instant.parse("2026-08-25T02:30:00Z")))
        .param("id", corrupted.applicationId())
        .update();

    MvcResult publicResult = publicFeed(token);
    assertGenericNotFound(publicResult);
    assertThat(calendar(publicResult)).doesNotContain("노출되면 안 되는 정상 항목");
    MvcResult detailResult =
        mvc.perform(get("/api/v1/calendar-feeds/{id}", created.path("id").asText())).andReturn();
    assertThat(detailResult.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(detailResult).path("code").asText())
        .isEqualTo("CALENDAR_FEED_PROJECTION_INTEGRITY_CONFLICT");
  }

  @Test
  void keepsPerFeedUidAndSequenceAcrossRemovalRestoreAndSourceCancellation() throws Exception {
    Seed event =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "복구 가능한 일정", Instant.parse("2026-09-03T09:00:00Z"), null);
    String secret = secret(4);
    JsonNode created =
        response(
            createFeed(
                "feed-tombstone-create", "복구 수신자", "TITLE", List.of(event.eventId()), secret));
    UUID feedId = UUID.fromString(created.path("id").asText());
    UUID entryId = UUID.fromString(created.at("/entries/0/id").asText());
    String publicUid = uid(feedId);

    JsonNode removed = remove(feedId, entryId, 1, "feed-remove");
    assertThat(removed.path("version").asLong()).isEqualTo(2);
    assertThat(removed.at("/entries/0/state").asText()).isEqualTo("CANCELLED");
    assertThat(removed.at("/entries/0/sequence").asInt()).isEqualTo(1);
    assertThat(removed.at("/entries/0/eventId").isNull()).isTrue();
    assertThat(unfold(calendar(publicFeed(secret))))
        .contains("UID:" + publicUid, "SEQUENCE:1", "STATUS:CANCELLED")
        .doesNotContain("SUMMARY:");

    JsonNode reactivated = add(feedId, event.eventId(), 2, "feed-readd");
    assertThat(reactivated.at("/entries/0/id").asText()).isEqualTo(entryId.toString());
    assertThat(reactivated.at("/entries/0/sequence").asInt()).isEqualTo(2);
    assertThat(uid(feedId)).isEqualTo(publicUid);

    assertThat(trashMemo(event.memoId(), "feed-source-trash").getResponse().getStatus())
        .isEqualTo(200);
    JsonNode afterTrash = detail(feedId);
    assertThat(afterTrash.path("version").asLong()).isEqualTo(4);
    assertThat(afterTrash.at("/entries/0/state").asText()).isEqualTo("CANCELLED");
    assertThat(afterTrash.at("/entries/0/sequence").asInt()).isEqualTo(3);

    assertThat(restoreMemo(event.memoId(), "feed-source-restore").getResponse().getStatus())
        .isEqualTo(200);
    JsonNode afterRestore = detail(feedId);
    assertThat(afterRestore.path("version").asLong()).isEqualTo(4);
    assertThat(afterRestore.at("/entries/0/state").asText()).isEqualTo("CANCELLED");

    JsonNode afterExplicitReactivation = add(feedId, event.eventId(), 4, "feed-restore-readd");
    assertThat(afterExplicitReactivation.path("version").asLong()).isEqualTo(5);
    assertThat(afterExplicitReactivation.at("/entries/0/sequence").asInt()).isEqualTo(4);
    assertThat(uid(feedId)).isEqualTo(publicUid);

    assertThat(undoApplication(event.applicationId(), "feed-source-undo").getResponse().getStatus())
        .isEqualTo(200);
    JsonNode afterUndo = detail(feedId);
    assertThat(afterUndo.path("version").asLong()).isEqualTo(6);
    assertThat(afterUndo.at("/entries/0/state").asText()).isEqualTo("CANCELLED");
    assertThat(afterUndo.at("/entries/0/sequence").asInt()).isEqualTo(5);
    assertThat(afterUndo.at("/entries/0/eventId").isNull()).isTrue();
    assertThat(unfold(calendar(publicFeed(secret))))
        .contains("UID:" + publicUid, "SEQUENCE:5", "STATUS:CANCELLED")
        .doesNotContain("SUMMARY:");

    Seed updated =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "수정으로 취소되는 일정", Instant.parse("2026-09-04T09:00:00Z"), null);
    JsonNode updateFeed =
        response(
            createFeed(
                "feed-update-hook-create", "수정 훅", "TITLE", List.of(updated.eventId()), secret(5)));
    UUID updateFeedId = UUID.fromString(updateFeed.path("id").asText());
    assertThat(updateMemo(updated.memoId(), 1, "수정된 fixture 메모").getResponse().getStatus())
        .isEqualTo(200);
    assertThat(detail(updateFeedId).at("/entries/0/state").asText()).isEqualTo("CANCELLED");
  }

  @Test
  void enforcesOwnerPairsAndManagementOwnerScopeInPostgres() throws Exception {
    UUID foreignOwner = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.parse("2026-08-25T03:00:00Z"));
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", foreignOwner)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) values(:id,'UTC',false)")
        .param("id", foreignOwner)
        .update();
    UUID foreignFeed = UUID.randomUUID();
    db.sql(
            """
            insert into calendar_feeds(
              id, owner_id, display_name, disclosure_mode, status, version, token_verifier,
              created_at, updated_at, rotated_at, revoked_at
            ) values (
              :id, :ownerId, 'foreign', 'BUSY_ONLY', 'ACTIVE', 1, :verifier,
              :now, :now, :now, null
            )
            """)
        .param("id", foreignFeed)
        .param("ownerId", foreignOwner)
        .param("verifier", CalendarFeedSecret.requireVerifier(secret(6)))
        .param("now", now)
        .update();

    MvcResult hidden = mvc.perform(get("/api/v1/calendar-feeds/{id}", foreignFeed)).andReturn();
    assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
    assertThat(response(mvc.perform(get("/api/v1/calendar-feeds")).andReturn())).isEmpty();

    assertThatThrownBy(
            () ->
                db.sql(
                        """
                        insert into calendar_feed_entries(
                          id, feed_id, owner_id, source_event_hash, public_uid,
                          active_memo_item_id, active_owner_id, active_item_kind, state, sequence,
                          schedule_kind, start_at_utc, end_at_utc, start_local_date,
                          end_local_date_exclusive, source_time_zone,
                          created_at, updated_at, cancelled_at
                        ) values (
                          :id, :feedId, :ownerId, :sourceHash, :uid,
                          null, null, null, 'CANCELLED', 1,
                          'TIMED', :startAt, null, null, null, 'UTC',
                          :now, :now, :now
                        )
                        """)
                    .param("id", UUID.randomUUID())
                    .param("feedId", foreignFeed)
                    .param("ownerId", OWNER_ID)
                    .param("sourceHash", "a".repeat(64))
                    .param("uid", "pm-feed-v1-" + secret(7) + "@personal-memo.invalid")
                    .param("startAt", Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")))
                    .param("now", now)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void ownerApplicationLockSerializesUndoShapedCancellationAgainstMembershipAdd() throws Exception {
    Seed event =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "동시성 일정", Instant.parse("2026-09-05T09:00:00Z"), null);
    JsonNode created =
        response(
            createFeed("feed-lock-create", "동시성", "TITLE", List.of(event.eventId()), secret(8)));
    UUID feedId = UUID.fromString(created.path("id").asText());
    CountDownLatch sourceLocked = new CountDownLatch(1);
    CountDownLatch addStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    try {
      Future<Void> source =
          executor.submit(
              () -> {
                transaction.executeWithoutResult(
                    ignored -> {
                      db.sql("select pg_advisory_xact_lock(hashtextextended(:scope, 0))")
                          .param("scope", OWNER_ID + ":ANALYSIS_APPLICATION_OWNER")
                          .query(
                              (resultSet, rowNumber) -> {
                                resultSet.getObject(1);
                                return rowNumber;
                              })
                          .single();
                      db.sql("select id from analysis_applications where id = :id for update")
                          .param("id", event.applicationId())
                          .query(UUID.class)
                          .single();
                      db.sql("select id from memos where id = :id for update")
                          .param("id", event.memoId())
                          .query(UUID.class)
                          .single();
                      sourceLocked.countDown();
                      await(addStarted);
                      projection.cancelForApplication(
                          OWNER_ID, event.applicationId(), Instant.parse("2026-08-25T04:00:00Z"));
                      db.sql("delete from event_details where memo_item_id = :id")
                          .param("id", event.eventId())
                          .update();
                      db.sql("delete from memo_items where id = :id")
                          .param("id", event.eventId())
                          .update();
                      db.sql(
                              "update analysis_applications set status = 'UNDONE', undone_at = :now where id = :id")
                          .param("now", Timestamp.from(Instant.parse("2026-08-25T04:00:00Z")))
                          .param("id", event.applicationId())
                          .update();
                    });
                return null;
              });
      assertThat(sourceLocked.await(5, TimeUnit.SECONDS)).isTrue();
      Future<Throwable> add =
          executor.submit(
              () -> {
                addStarted.countDown();
                try {
                  management.addEvent(
                      feedId,
                      "feed-lock-add",
                      new AddEvent(event.eventId(), created.path("version").asLong()));
                  return null;
                } catch (Throwable exception) {
                  return exception;
                }
              });

      source.get(10, TimeUnit.SECONDS);
      Throwable addFailure = add.get(10, TimeUnit.SECONDS);
      assertThat(addFailure)
          .isInstanceOfSatisfying(
              DomainException.class,
              exception ->
                  assertThat(exception.code()).isEqualTo("CALENDAR_FEED_EVENT_NOT_ELIGIBLE"));
      assertThat(detail(feedId).at("/entries/0/state").asText()).isEqualTo("CANCELLED");
    } finally {
      executor.shutdownNow();
    }
  }

  private MvcResult createFeed(
      String key, String displayName, String disclosureMode, List<UUID> eventIds, String secret)
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
                            disclosureMode,
                            "eventIds",
                            eventIds,
                            "bearerSecret",
                            secret))))
        .andReturn();
  }

  private JsonNode remove(UUID feedId, UUID entryId, long version, String key) throws Exception {
    return response(
        mvc.perform(
                post("/api/v1/calendar-feeds/{id}/events/{entryId}/remove", feedId, entryId)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("expectedVersion", version))))
            .andReturn());
  }

  private JsonNode add(UUID feedId, UUID eventId, long version, String key) throws Exception {
    return response(
        mvc.perform(
                post("/api/v1/calendar-feeds/{id}/events", feedId)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of("eventId", eventId, "expectedVersion", version))))
            .andReturn());
  }

  private JsonNode detail(UUID feedId) throws Exception {
    return response(mvc.perform(get("/api/v1/calendar-feeds/{id}", feedId)).andReturn());
  }

  private MvcResult publicFeed(String token) throws Exception {
    return mvc.perform(get("/calendar/v1/feed.ics").queryParam("token", token)).andReturn();
  }

  private String calendar(MvcResult result) {
    return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private String unfold(String value) {
    return value.replace("\r\n ", "");
  }

  private String uid(UUID feedId) {
    return db.sql("select public_uid from calendar_feed_entries where feed_id = :feedId")
        .param("feedId", feedId)
        .query(String.class)
        .single();
  }

  private ReadSnapshot readSnapshot(UUID feedId) {
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
                   (select count(*) from event_details) as event_count
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
                new ReadSnapshot(
                    resultSet.getLong("version"),
                    resultSet.getTimestamp("feed_updated_at").toInstant(),
                    resultSet.getString("state"),
                    resultSet.getInt("sequence"),
                    resultSet.getTimestamp("entry_updated_at").toInstant(),
                    resultSet.getLong("memo_count"),
                    resultSet.getLong("revision_count"),
                    resultSet.getLong("application_count"),
                    resultSet.getLong("item_count"),
                    resultSet.getLong("event_count")))
        .single();
  }

  private String secret(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void assertGenericNotFound(MvcResult result) {
    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(result.getResponse().getContentType()).isNull();
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out while arranging the lock-order fixture.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while arranging the lock-order fixture.", exception);
    }
  }

  private record ReadSnapshot(
      long version,
      Instant feedUpdatedAt,
      String entryState,
      int entrySequence,
      Instant entryUpdatedAt,
      long memoCount,
      long revisionCount,
      long applicationCount,
      long itemCount,
      long eventCount) {}
}
