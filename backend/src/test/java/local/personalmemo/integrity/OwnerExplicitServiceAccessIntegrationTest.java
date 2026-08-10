package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.idempotency.IdempotencyService;
import local.personalmemo.memo.application.MemoService;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@PostgresIntegration
class OwnerExplicitServiceAccessIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID OTHER_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Autowired private IdempotencyService idempotency;
  @Autowired private MemoService memos;

  @Test
  @Transactional
  void ownerExplicitIdempotencyMethodsKeepIdenticalOperationAndKeyIsolatedByOwner() {
    insertOtherOwner();
    String operation = "BACKGROUND_RECOVERY_TEST";
    String key = "shared-recovery-key";
    UUID firstResourceId = UUID.randomUUID();
    UUID secondResourceId = UUID.randomUUID();

    assertThat(idempotency.find(OWNER_ID, operation, key, "first-hash")).isEmpty();
    idempotency.store(
        OWNER_ID, operation, key, "first-hash", firstResourceId, Map.of("status", "RUNNING"));
    assertThat(idempotency.find(OTHER_OWNER_ID, operation, key, "second-hash")).isEmpty();
    idempotency.store(
        OTHER_OWNER_ID,
        operation,
        key,
        "second-hash",
        secondResourceId,
        Map.of("status", "RUNNING"));

    idempotency.complete(
        OTHER_OWNER_ID,
        operation,
        key,
        "second-hash",
        secondResourceId,
        Map.of("status", "COMPLETED"));

    var first = idempotency.find(OWNER_ID, operation, key, "first-hash").orElseThrow();
    var second = idempotency.find(OTHER_OWNER_ID, operation, key, "second-hash").orElseThrow();
    assertThat(first.resourceId()).isEqualTo(firstResourceId);
    assertThat(first.response().path("status").asText()).isEqualTo("RUNNING");
    assertThat(second.resourceId()).isEqualTo(secondResourceId);
    assertThat(second.response().path("status").asText()).isEqualTo("COMPLETED");
    assertThat(
            db.sql(
                    "select count(*) from idempotency_records "
                        + "where operation=:operation and idempotency_key=:key")
                .param("operation", operation)
                .param("key", key)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  @Transactional
  void ownerExplicitMemoLockReadsOnlyTheProvidedOwnersMemo() {
    insertOtherOwner();
    UUID memoId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.parse("2026-08-10T12:00:00Z"));
    db.sql(
            "insert into memos(id,owner_id,current_revision,status,pinned,created_at,updated_at) "
                + "values(:id,:owner,1,'ACTIVE',false,:now,:now)")
        .param("id", memoId)
        .param("owner", OTHER_OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into memo_revisions("
                + "memo_id,owner_id,revision,content,content_hash,created_at,created_by,"
                + "client_recorded_at,source_time_zone) "
                + "values(:id,:owner,1,'복구 대상 메모',:hash,:now,:owner,:now,'Asia/Seoul')")
        .param("id", memoId)
        .param("owner", OTHER_OWNER_ID)
        .param("hash", "owner-explicit-test-hash")
        .param("now", now)
        .update();

    assertThat(memos.getCurrentForUpdate(OTHER_OWNER_ID, memoId).content()).isEqualTo("복구 대상 메모");
    assertThatThrownBy(() -> memos.getCurrentForUpdate(memoId))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("RESOURCE_NOT_FOUND"));
  }

  private void insertOtherOwner() {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-10T12:00:00Z"));
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", OTHER_OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:id,'Asia/Seoul',false)")
        .param("id", OTHER_OWNER_ID)
        .update();
  }
}
