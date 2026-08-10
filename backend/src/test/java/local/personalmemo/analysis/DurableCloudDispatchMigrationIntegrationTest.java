package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@PostgresIntegration
class DurableCloudDispatchMigrationIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID MEMO_ID = UUID.fromString("30000000-0000-0000-0000-000000000015");
  private static final UUID LEGACY_RUN = UUID.fromString("41000000-0000-0000-0000-000000000015");
  private static final UUID SNAPSHOT_RUN = UUID.fromString("42000000-0000-0000-0000-000000000015");
  private static final Instant PREPARED_AT = Instant.parse("2026-08-10T01:00:00Z");

  @Autowired private DataSource dataSource;

  private String isolatedSchema;

  @AfterEach
  void removeIsolatedSchema() throws SQLException {
    if (isolatedSchema == null || !isolatedSchema.startsWith("v15_dispatch_")) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema " + isolatedSchema + " cascade");
    }
  }

  @Test
  void v15PreservesV14TruthAndEnforcesDurableRunLifecycle() throws Exception {
    JdbcClient isolated = migrateV14FixtureToLatest();

    assertThat(readRunEvidence(isolated))
        .containsExactlyInAnyOrder(
            new RunEvidence(LEGACY_RUN, "legacy-v0", "NOT_REQUIRED", "REVIEW_REQUIRED"),
            new RunEvidence(SNAPSHOT_RUN, "snapshot-v1", "SUCCESS", "REVIEW_REQUIRED"));
    assertThat(
            isolated
                .sql("select count(*) from " + table("analysis_run_dispatches"))
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            isolated
                .sql(
                    "select indexdef from pg_indexes where schemaname=:schema and indexname='idx_analysis_run_dispatches_recovery'")
                .param("schema", isolatedSchema)
                .query(String.class)
                .single())
        .contains("state", "lease_expires_at", "deadline_at")
        .contains("PREPARED")
        .contains("RUNNING");

    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_runs")
                        + " set cloud_outcome='PENDING' where id=:id")
                .param("id", SNAPSHOT_RUN)
                .update());
    assertRejected(
        () ->
            isolated
                .sql("update " + table("analysis_runs") + " set status='NOT_A_STATUS' where id=:id")
                .param("id", LEGACY_RUN)
                .update());

    UUID durableRun = UUID.randomUUID();
    insertDurableRun(isolated, durableRun, "1");
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_runs")
                        + " set completed_at=:completedAt where id=:id")
                .param("completedAt", Timestamp.from(PREPARED_AT.plusSeconds(1)))
                .param("id", durableRun)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_runs")
                        + " set cloud_outcome='SUCCESS' where id=:id")
                .param("id", durableRun)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_runs")
                        + " set cloud_outcome='CANCELLED_STALE', completed_at=:completedAt where id=:id")
                .param("completedAt", Timestamp.from(PREPARED_AT.plusSeconds(1)))
                .param("id", durableRun)
                .update());

    isolated
        .sql(
            "update "
                + table("analysis_runs")
                + " set status='STALE', cloud_outcome='CANCELLED_STALE', completed_at=:completedAt where id=:id")
        .param("completedAt", Timestamp.from(PREPARED_AT.plusSeconds(1)))
        .param("id", durableRun)
        .update();
    assertThat(readRunEvidence(isolated))
        .contains(new RunEvidence(durableRun, "durable-v1", "CANCELLED_STALE", "STALE"));
  }

  @Test
  void dispatchConstraintsProtectOwnershipIdentityPayloadBoundsAndFencing() throws Exception {
    JdbcClient isolated = migrateV14FixtureToLatest();
    UUID firstRun = UUID.randomUUID();
    UUID secondRun = UUID.randomUUID();
    insertDurableRun(isolated, firstRun, "2");
    insertDurableRun(isolated, secondRun, "3");

    UUID firstProposal = UUID.randomUUID();
    insertPreparedDispatch(isolated, firstRun, OWNER_ID, firstProposal, "a", "b", "c", "d");

    assertRejected(
        () ->
            insertPreparedDispatch(
                isolated, firstRun, OWNER_ID, UUID.randomUUID(), "e", "f", "1", "2"));
    assertRejected(
        () ->
            insertPreparedDispatch(
                isolated, secondRun, OWNER_ID, UUID.randomUUID(), "a", "e", "f", "1"));
    assertRejected(
        () ->
            insertPreparedDispatch(
                isolated, secondRun, OWNER_ID, firstProposal, "e", "f", "1", "2"));
    assertRejected(
        () ->
            insertPreparedDispatch(
                isolated, secondRun, UUID.randomUUID(), UUID.randomUUID(), "e", "f", "1", "2"));

    assertRejected(() -> updateDispatchText(isolated, firstRun, "idempotency_key_hash", "bad"));
    assertRejected(() -> updateDispatchText(isolated, firstRun, "request_hash", "bad"));
    assertRejected(
        () -> updateDispatchText(isolated, firstRun, "validated_local_proposal_hash", "bad"));
    assertRejected(
        () -> updateDispatchText(isolated, firstRun, "executor_binding_id", "not-a-binding"));
    assertRejected(
        () -> updateDispatchText(isolated, firstRun, "validated_local_proposal", "not-json"));
    assertRejected(
        () ->
            updateDispatchText(
                isolated,
                firstRun,
                "validated_local_proposal",
                "{\"value\":\"" + "x".repeat(65_536) + "\"}"));
    assertRejected(() -> updateDispatchInteger(isolated, firstRun, "call_timeout_ms", 0));
    assertRejected(() -> updateDispatchInteger(isolated, firstRun, "call_timeout_ms", 60_001));
    assertRejected(() -> updateDispatchInteger(isolated, firstRun, "max_attempts", 0));
    assertRejected(() -> updateDispatchInteger(isolated, firstRun, "max_attempts", 11));
    assertRejected(() -> updateDispatchLong(isolated, firstRun, "fence_token", 4));
    assertRejected(() -> updateDispatchText(isolated, firstRun, "state", "UNKNOWN"));
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set deadline_at=:deadline where analysis_run_id=:runId")
                .param("deadline", Timestamp.from(PREPARED_AT.plusMillis(999)))
                .param("runId", firstRun)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set state='RUNNING', fence_token=1 where analysis_run_id=:runId")
                .param("runId", firstRun)
                .update());

    Instant attemptAt = PREPARED_AT.plusSeconds(1);
    Instant leaseAt = PREPARED_AT.plusSeconds(3);
    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set state='RUNNING', fence_token=1, last_attempt_started_at=:attemptAt, lease_expires_at=:leaseAt, updated_at=:attemptAt where analysis_run_id=:runId")
        .param("attemptAt", Timestamp.from(attemptAt))
        .param("leaseAt", Timestamp.from(leaseAt))
        .param("runId", firstRun)
        .update();
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set lease_expires_at=:leaseAt where analysis_run_id=:runId")
                .param("leaseAt", Timestamp.from(PREPARED_AT))
                .param("runId", firstRun)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set updated_at=:updatedAt where analysis_run_id=:runId")
                .param("updatedAt", Timestamp.from(PREPARED_AT))
                .param("runId", firstRun)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set last_attempt_started_at=deadline_at where analysis_run_id=:runId")
                .param("runId", firstRun)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set lease_expires_at=deadline_at + interval '1 millisecond' where analysis_run_id=:runId")
                .param("runId", firstRun)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set state='FINALIZED', lease_expires_at=null where analysis_run_id=:runId")
                .param("runId", firstRun)
                .update());

    Instant finalizedAt = PREPARED_AT.plusSeconds(4);
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set state='FINALIZED', lease_expires_at=null, finalized_at=:finalizedAt, updated_at=:finalizedAt where analysis_run_id=:runId")
                .param("finalizedAt", Timestamp.from(finalizedAt))
                .param("runId", firstRun)
                .update());
    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set state='FINALIZED', validated_local_proposal=null, lease_expires_at=null, finalized_at=:finalizedAt, updated_at=:finalizedAt where analysis_run_id=:runId")
        .param("finalizedAt", Timestamp.from(finalizedAt))
        .param("runId", firstRun)
        .update();
    assertThat(
            isolated
                .sql(
                    "select state from "
                        + table("analysis_run_dispatches")
                        + " where analysis_run_id=:runId")
                .param("runId", firstRun)
                .query(String.class)
                .single())
        .isEqualTo("FINALIZED");
  }

  private JdbcClient migrateV14FixtureToLatest() throws SQLException {
    isolatedSchema = "v15_dispatch_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + isolatedSchema);
    }
    migrateThroughVersion14();
    JdbcClient isolated = JdbcClient.create(dataSource);
    seedVersion14Rows(isolated);
    migrateToLatest();
    return isolated;
  }

  private void migrateThroughVersion14() {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(isolatedSchema)
        .defaultSchema(isolatedSchema)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("14"))
        .load()
        .migrate();
  }

  private void migrateToLatest() {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(isolatedSchema)
        .defaultSchema(isolatedSchema)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private void seedVersion14Rows(JdbcClient isolated) {
    Timestamp now = Timestamp.from(PREPARED_AT);
    isolated
        .sql(
            "insert into "
                + table("memos")
                + "(id,owner_id,current_revision,status,pinned,created_at,updated_at) values(:id,:owner,1,'ACTIVE',false,:now,:now)")
        .param("id", MEMO_ID)
        .param("owner", OWNER_ID)
        .param("now", now)
        .update();
    isolated
        .sql(
            "insert into "
                + table("memo_revisions")
                + "(memo_id,owner_id,revision,content,content_hash,created_at,created_by,client_recorded_at,source_time_zone) values(:memo,:owner,1,'v15 fixture',repeat('a',64),:now,:owner,:now,'Asia/Seoul')")
        .param("memo", MEMO_ID)
        .param("owner", OWNER_ID)
        .param("now", now)
        .update();
    isolated
        .sql(
            "insert into "
                + table("analysis_runs")
                + "(id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,ambiguity_reasons,created_at,completed_at,cloud_execution_contract_version) values(:id,:owner,:memo,1,'LOCAL','REVIEW_REQUIRED','2','v14-local','[]',:now,:now,'legacy-v0')")
        .param("id", LEGACY_RUN)
        .param("owner", OWNER_ID)
        .param("memo", MEMO_ID)
        .param("now", now)
        .update();
    isolated
        .sql(
            "insert into "
                + table("analysis_runs")
                + "(id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,ambiguity_reasons,created_at,completed_at,cloud_transfer_mode,cloud_gateway_version,cloud_provider_id,cloud_model_version,cloud_consent_policy_version,cloud_outcome,cloud_execution_contract_version,cloud_provider_request_token) values(:id,:owner,:memo,1,'HYBRID','REVIEW_REQUIRED','2','v14-cloud','[]',:now,:now,'NO_NETWORK','v14-gateway','fake','none','no-network-v1','SUCCESS','snapshot-v1',:token)")
        .param("id", SNAPSHOT_RUN)
        .param("owner", OWNER_ID)
        .param("memo", MEMO_ID)
        .param("now", now)
        .param("token", "pmr1_" + "0".repeat(64))
        .update();
  }

  private void insertDurableRun(JdbcClient isolated, UUID runId, String tokenCharacter) {
    Timestamp now = Timestamp.from(PREPARED_AT);
    isolated
        .sql(
            "insert into "
                + table("analysis_runs")
                + "(id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,prompt_version,local_model_version,embedding_model_version,routing_policy_version,cloud_transfer_mode,cloud_gateway_version,cloud_provider_id,cloud_model_version,cloud_consent_policy_version,cloud_outcome,cloud_execution_contract_version,cloud_provider_request_token,ambiguity_reasons,created_at,completed_at) values(:id,:owner,:memo,1,'HYBRID','QUEUED','2','v15-analyzer','none','none','none','v15-policy','NO_NETWORK','v15-gateway','fake','none','no-network-v1','PENDING','durable-v1',:token,'[]',:now,null)")
        .param("id", runId)
        .param("owner", OWNER_ID)
        .param("memo", MEMO_ID)
        .param("token", "pmr1_" + tokenCharacter.repeat(64))
        .param("now", now)
        .update();
  }

  private void insertPreparedDispatch(
      JdbcClient isolated,
      UUID runId,
      UUID ownerId,
      UUID proposalId,
      String keyHashCharacter,
      String requestHashCharacter,
      String proposalHashCharacter,
      String bindingCharacter) {
    isolated
        .sql(
            "insert into "
                + table("analysis_run_dispatches")
                + "(analysis_run_id,owner_id,reserved_proposal_id,idempotency_key_hash,request_hash,validated_local_proposal,validated_local_proposal_hash,executor_binding_id,call_timeout_ms,max_attempts,deadline_at,state,fence_token,last_attempt_started_at,lease_expires_at,prepared_at,finalized_at,updated_at) values(:runId,:ownerId,:proposalId,:keyHash,:requestHash,:proposal,:proposalHash,:bindingId,1000,3,:deadline,'PREPARED',0,null,null,:preparedAt,null,:preparedAt)")
        .param("runId", runId)
        .param("ownerId", ownerId)
        .param("proposalId", proposalId)
        .param("keyHash", keyHashCharacter.repeat(64))
        .param("requestHash", requestHashCharacter.repeat(64))
        .param("proposal", "{\"schemaVersion\":\"2\"}")
        .param("proposalHash", proposalHashCharacter.repeat(64))
        .param("bindingId", "cgb1_" + bindingCharacter.repeat(64))
        .param("deadline", Timestamp.from(PREPARED_AT.plusSeconds(10)))
        .param("preparedAt", Timestamp.from(PREPARED_AT))
        .update();
  }

  private void updateDispatchText(JdbcClient isolated, UUID runId, String column, String value) {
    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set "
                + column
                + "=:value where analysis_run_id=:runId")
        .param("value", value)
        .param("runId", runId)
        .update();
  }

  private void updateDispatchInteger(JdbcClient isolated, UUID runId, String column, int value) {
    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set "
                + column
                + "=:value where analysis_run_id=:runId")
        .param("value", value)
        .param("runId", runId)
        .update();
  }

  private void updateDispatchLong(JdbcClient isolated, UUID runId, String column, long value) {
    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set "
                + column
                + "=:value where analysis_run_id=:runId")
        .param("value", value)
        .param("runId", runId)
        .update();
  }

  private List<RunEvidence> readRunEvidence(JdbcClient isolated) {
    return isolated
        .sql(
            "select id,cloud_execution_contract_version,cloud_outcome,status from "
                + table("analysis_runs"))
        .query(
            (resultSet, rowNumber) ->
                new RunEvidence(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("cloud_execution_contract_version"),
                    resultSet.getString("cloud_outcome"),
                    resultSet.getString("status")))
        .list();
  }

  private void assertRejected(Runnable mutation) {
    assertThatThrownBy(mutation::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private String table(String tableName) {
    return isolatedSchema + "." + tableName;
  }

  private record RunEvidence(UUID runId, String contractVersion, String outcome, String status) {}
}
