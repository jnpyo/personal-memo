package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresIntegration
class UndoOrphanTagIntegrationTest extends PostgresIntegrationTestSupport {

  @Autowired private DataSource dataSource;

  @ParameterizedTest(name = "creator application is undone first: {0}")
  @ValueSource(booleans = {true, false})
  void removesApplicationCreatedTagAfterItsLastUseRegardlessOfUndoOrder(
      boolean creatorApplicationFirst) throws Exception {
    UUID creatorApplication = applyWithNewTag("undo-tag-creator", "공유 뒤 정리 태그");
    UUID tagId =
        db.sql("select id from tags where owner_id=:ownerId and normalized_name=:name")
            .param("ownerId", OWNER_ID)
            .param("name", "공유 뒤 정리 태그")
            .query(UUID.class)
            .single();
    UUID reusingApplication = applyWithExistingTag("undo-tag-reuser", tagId);

    UUID first = creatorApplicationFirst ? creatorApplication : reusingApplication;
    UUID second = creatorApplicationFirst ? reusingApplication : creatorApplication;
    undoApplication(first, "undo-tag-first-" + creatorApplicationFirst);
    assertThat(tagCount(tagId)).isEqualTo(1);

    undoApplication(second, "undo-tag-second-" + creatorApplicationFirst);

    assertThat(tagCount(tagId)).isZero();
    assertThat(
            db.sql("select count(*) from item_tags where owner_id=:ownerId and tag_id=:tagId")
                .param("ownerId", OWNER_ID)
                .param("tagId", tagId)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void concurrentUndoOfDifferentApplicationsIsSerializedPerOwner() throws Exception {
    UUID creatorApplication =
        applyWithNewTag("concurrent-undo-tag-creator", "concurrent shared cleanup tag");
    UUID tagId =
        db.sql("select id from tags where owner_id=:ownerId and normalized_name=:name")
            .param("ownerId", OWNER_ID)
            .param("name", "concurrent shared cleanup tag")
            .query(UUID.class)
            .single();
    UUID reusingApplication = applyWithExistingTag("concurrent-undo-tag-reuser", tagId);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2);
        Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      acquireOwnerApplicationLock(blocker);

      var creatorUndo =
          executor.submit(
              () -> {
                ready.countDown();
                go.await();
                return undoApplication(creatorApplication, "concurrent-undo-creator");
              });
      var reuserUndo =
          executor.submit(
              () -> {
                ready.countDown();
                go.await();
                return undoApplication(reusingApplication, "concurrent-undo-reuser");
              });

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      boolean bothRequestsWaitedForOwnerLock;
      try {
        bothRequestsWaitedForOwnerLock = awaitWaitingAdvisoryLocks(2, 5, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
      }

      var creatorResult = creatorUndo.get(15, TimeUnit.SECONDS);
      var reuserResult = reuserUndo.get(15, TimeUnit.SECONDS);
      assertThat(bothRequestsWaitedForOwnerLock).isTrue();
      assertThat(creatorResult.getResponse().getStatus()).isEqualTo(200);
      assertThat(reuserResult.getResponse().getStatus()).isEqualTo(200);
    }

    assertThat(tagCount(tagId)).isZero();
    assertThat(
            db.sql("select count(*) from item_tags where owner_id=:ownerId and tag_id=:tagId")
                .param("ownerId", OWNER_ID)
                .param("tagId", tagId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql(
                    "select count(*) from analysis_applications "
                        + "where owner_id=:ownerId and id in (:first,:second) and status='UNDONE'")
                .param("ownerId", OWNER_ID)
                .param("first", creatorApplication)
                .param("second", reusingApplication)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  private void acquireOwnerApplicationLock(Connection connection) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(1, OWNER_ID + ":ANALYSIS_APPLICATION_OWNER");
      statement.execute();
    }
  }

  private boolean awaitWaitingAdvisoryLocks(long expectedCount, long timeout, TimeUnit timeUnit)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      long waiting =
          db.sql("select count(*) from pg_locks where locktype='advisory' and not granted")
              .query(Long.class)
              .single();
      if (waiting >= expectedCount) {
        return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private UUID applyWithNewTag(String keyPrefix, String tagName) throws Exception {
    return apply(keyPrefix, Map.of("newCanonicalName", tagName));
  }

  private UUID applyWithExistingTag(String keyPrefix, UUID tagId) throws Exception {
    return apply(keyPrefix, Map.of("existingTagId", tagId));
  }

  private UUID apply(String keyPrefix, Map<String, Object> tagSelection) throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, keyPrefix + "-create", keyPrefix + " 작업");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, keyPrefix + "-start", 1)).path("proposalId").asText());
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", keyPrefix + " 작업");
    item.put("due", null);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision",
            1,
            "selectedType",
            "TASK",
            "title",
            keyPrefix + " 작업",
            "selectedTags",
            List.of(tagSelection),
            "items",
            List.of(item));
    var result = applyProposal(proposalId, keyPrefix + "-apply", selection);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return UUID.fromString(response(result).path("applicationId").asText());
  }

  private long tagCount(UUID tagId) {
    return db.sql("select count(*) from tags where id=:tagId and owner_id=:ownerId")
        .param("tagId", tagId)
        .param("ownerId", OWNER_ID)
        .query(Long.class)
        .single();
  }
}
