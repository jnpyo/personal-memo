package local.personalmemo.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import javax.sql.DataSource;
import local.personalmemo.analysis.application.AnalysisApplicationService;
import local.personalmemo.calendar.CalendarFeedTestData.Seed;
import local.personalmemo.calendar.api.CalendarFeedDtos.AddEvent;
import local.personalmemo.calendar.api.CalendarFeedDtos.Create;
import local.personalmemo.calendar.api.CalendarFeedDtos.FeedDetail;
import local.personalmemo.calendar.application.CalendarFeedManagementService;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.memo.api.MemoDtos;
import local.personalmemo.memo.application.MemoService;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@PostgresIntegration
class CalendarFeedSourceConcurrencyIntegrationTest extends PostgresIntegrationTestSupport {
  private static final String OWNER_LOCK_SCOPE = OWNER_ID + ":ANALYSIS_APPLICATION_OWNER";

  @Autowired private CalendarFeedManagementService feeds;
  @Autowired private MemoService memos;
  @Autowired private AnalysisApplicationService applications;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  @Test
  void integrationContextsUseTheTestOnlyBoundedConnectionPool() {
    assertThat(dataSource)
        .isInstanceOfSatisfying(
            HikariDataSource.class,
            pool -> {
              assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
              assertThat(pool.getMinimumIdle()).isZero();
            });
  }

  @ParameterizedTest(name = "{0} versus actual application undo")
  @EnumSource(FeedMutation.class)
  void createOrAddVersusActualUndoNeverLeavesAStaleActiveShare(FeedMutation feedMutation)
      throws Exception {
    Seed target =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "undo race target", Instant.parse("2026-09-10T09:00:00Z"), null);
    FeedOperation feedOperation = prepareFeedOperation(feedMutation, target, "undo");

    RaceResult<FeedDetail, Object> race =
        raceBehindOwnerApplicationLock(
            feedOperation.operation(),
            () -> applications.undo(target.applicationId(), "undo-race-" + feedMutation));

    assertEligibleRaceOutcome(race.first());
    assertThat(race.second().failure()).isNull();
    assertThat(
            db.sql("select status from analysis_applications where id = :id")
                .param("id", target.applicationId())
                .query(String.class)
                .single())
        .isEqualTo("UNDONE");
    assertThat(
            db.sql("select count(*) from memo_items where id = :id")
                .param("id", target.eventId())
                .query(Long.class)
                .single())
        .isZero();
    assertNoStaleActiveShare(target.eventId());
    assertFeedOutcomeMatchesCommittedState(feedMutation, feedOperation.feedId(), race.first());
  }

  @ParameterizedTest(name = "{0} versus memo {1}")
  @MethodSource("feedAndMemoMutations")
  void createOrAddVersusMemoMutationNeverLeavesAStaleActiveShare(
      FeedMutation feedMutation, MemoMutation memoMutation) throws Exception {
    Seed target =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "memo race target", Instant.parse("2026-09-11T09:00:00Z"), null);
    FeedOperation feedOperation = prepareFeedOperation(feedMutation, target, memoMutation.name());

    RaceResult<FeedDetail, Object> race =
        raceBehindMemoLock(
            target.memoId(),
            feedOperation.operation(),
            () -> mutateMemo(target.memoId(), memoMutation));

    assertEligibleRaceOutcome(race.first());
    assertThat(race.second().failure()).isNull();
    assertThat(
            db.sql("select status from memos where id = :id")
                .param("id", target.memoId())
                .query(String.class)
                .single())
        .isEqualTo(memoMutation == MemoMutation.TRASH ? "TRASHED" : "ACTIVE");
    assertThat(
            db.sql("select current_revision from memos where id = :id")
                .param("id", target.memoId())
                .query(Integer.class)
                .single())
        .isEqualTo(memoMutation == MemoMutation.UPDATE ? 2 : 1);
    assertNoStaleActiveShare(target.eventId());
    assertFeedOutcomeMatchesCommittedState(feedMutation, feedOperation.feedId(), race.first());
  }

  private static Stream<Arguments> feedAndMemoMutations() {
    return Stream.of(
        Arguments.of(FeedMutation.CREATE, MemoMutation.UPDATE),
        Arguments.of(FeedMutation.CREATE, MemoMutation.TRASH),
        Arguments.of(FeedMutation.ADD, MemoMutation.UPDATE),
        Arguments.of(FeedMutation.ADD, MemoMutation.TRASH));
  }

  private FeedOperation prepareFeedOperation(FeedMutation mutation, Seed target, String keySuffix) {
    if (mutation == FeedMutation.CREATE) {
      return new FeedOperation(
          null,
          () ->
              feeds.create(
                  "concurrent-create-" + keySuffix,
                  new Create(
                      "concurrent recipient", "TITLE", List.of(target.eventId()), secret(10))));
    }

    Seed anchor =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "unrelated active anchor", Instant.parse("2026-09-12T09:00:00Z"), null);
    FeedDetail created =
        feeds.create(
            "concurrent-anchor-" + keySuffix,
            new Create("concurrent recipient", "TITLE", List.of(anchor.eventId()), secret(11)));
    return new FeedOperation(
        created.id(),
        () ->
            feeds.addEvent(
                created.id(),
                "concurrent-add-" + keySuffix,
                new AddEvent(target.eventId(), created.version())));
  }

  private Object mutateMemo(UUID memoId, MemoMutation mutation) {
    return switch (mutation) {
      case UPDATE ->
          memos.update(
              memoId,
              "concurrent-memo-update",
              new MemoDtos.Update(1, "synthetic updated capture", null, null));
      case TRASH -> memos.trash(memoId, "concurrent-memo-trash");
    };
  }

  private <A, B> RaceResult<A, B> raceBehindOwnerApplicationLock(
      Callable<A> firstOperation, Callable<B> secondOperation) throws Exception {
    return raceBehindDatabaseLock(
        () ->
            db.sql("select pg_advisory_xact_lock(hashtextextended(:scope, 0))")
                .param("scope", OWNER_LOCK_SCOPE)
                .query(
                    (resultSet, rowNumber) -> {
                      resultSet.getObject(1);
                      return rowNumber;
                    })
                .single(),
        firstOperation,
        secondOperation);
  }

  private <A, B> RaceResult<A, B> raceBehindMemoLock(
      UUID memoId, Callable<A> firstOperation, Callable<B> secondOperation) throws Exception {
    return raceBehindDatabaseLock(
        () ->
            db.sql("select id from memos where id = :id and owner_id = :ownerId for update")
                .param("id", memoId)
                .param("ownerId", OWNER_ID)
                .query(UUID.class)
                .single(),
        firstOperation,
        secondOperation);
  }

  private <A, B> RaceResult<A, B> raceBehindDatabaseLock(
      Callable<?> acquireGate, Callable<A> firstOperation, Callable<B> secondOperation)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(3);
    CountDownLatch gateAcquired = new CountDownLatch(1);
    CountDownLatch releaseGate = new CountDownLatch(1);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    try {
      Future<Void> gate =
          executor.submit(
              () -> {
                transaction.executeWithoutResult(
                    ignored -> {
                      callUnchecked(acquireGate);
                      gateAcquired.countDown();
                      await(releaseGate);
                    });
                return null;
              });
      assertThat(gateAcquired.await(5, TimeUnit.SECONDS)).isTrue();

      Future<Attempt<A>> first = executor.submit(() -> attempt(firstOperation));
      Future<Attempt<B>> second = executor.submit(() -> attempt(secondOperation));
      try {
        awaitDatabaseLockWaiters(2);
      } finally {
        releaseGate.countDown();
      }

      gate.get(10, TimeUnit.SECONDS);
      return new RaceResult<>(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    } finally {
      releaseGate.countDown();
      executor.shutdownNow();
    }
  }

  private void awaitDatabaseLockWaiters(long expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    long observed = 0;
    while (System.nanoTime() < deadline) {
      observed =
          db.sql(
                  "select count(*) from pg_stat_activity "
                      + "where datname = current_database() "
                      + "and pid <> pg_backend_pid() and wait_event_type = 'Lock'")
              .query(Long.class)
              .single();
      if (observed >= expected) {
        return;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
    }
    throw new AssertionError(
        "Expected at least " + expected + " database lock waiters but observed " + observed);
  }

  private void assertEligibleRaceOutcome(Attempt<FeedDetail> attempt) {
    if (attempt.failure() == null) {
      assertThat(attempt.value()).isNotNull();
      return;
    }
    assertThat(attempt.failure())
        .isInstanceOfSatisfying(
            DomainException.class,
            exception ->
                assertThat(exception.code()).isEqualTo("CALENDAR_FEED_EVENT_NOT_ELIGIBLE"));
  }

  private void assertFeedOutcomeMatchesCommittedState(
      FeedMutation mutation, UUID existingFeedId, Attempt<FeedDetail> attempt) {
    long feedCount =
        db.sql("select count(*) from calendar_feeds where owner_id = :ownerId")
            .param("ownerId", OWNER_ID)
            .query(Long.class)
            .single();
    if (mutation == FeedMutation.CREATE) {
      assertThat(feedCount).isEqualTo(attempt.failure() == null ? 1 : 0);
      return;
    }

    assertThat(feedCount).isEqualTo(1);
    assertThat(
            db.sql(
                    "select count(*) from calendar_feed_entries "
                        + "where feed_id = :feedId and state = 'ACTIVE'")
                .param("feedId", existingFeedId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  private void assertNoStaleActiveShare(UUID targetEventId) {
    assertThat(
            db.sql(
                    "select count(*) from calendar_feed_entries "
                        + "where state = 'ACTIVE' and active_memo_item_id = :eventId")
                .param("eventId", targetEventId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql(
                    """
                    select count(*)
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
                     where entry.state = 'ACTIVE'
                       and (
                         event.memo_item_id is null
                         or item.archived_at is not null
                         or memo.status <> 'ACTIVE'
                         or item.memo_revision <> memo.current_revision
                         or application.status <> 'APPLIED'
                       )
                    """)
                .query(Long.class)
                .single())
        .isZero();
  }

  private <T> Attempt<T> attempt(Callable<T> operation) {
    try {
      return new Attempt<>(operation.call(), null);
    } catch (Throwable exception) {
      return new Attempt<>(null, exception);
    }
  }

  private Object callUnchecked(Callable<?> operation) {
    try {
      return operation.call();
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out while holding the concurrency gate.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while holding the concurrency gate.", exception);
    }
  }

  private String secret(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private enum FeedMutation {
    CREATE,
    ADD
  }

  private enum MemoMutation {
    UPDATE,
    TRASH
  }

  private record FeedOperation(UUID feedId, Callable<FeedDetail> operation) {}

  private record Attempt<T>(T value, Throwable failure) {}

  private record RaceResult<A, B>(Attempt<A> first, Attempt<B> second) {}
}
