package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import local.personalmemo.common.security.Hashing;
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
class CloudGatewayAttemptObservabilityMigrationIntegrationTest
    extends PostgresIntegrationTestSupport {
  private static final UUID MEMO_ID = UUID.fromString("30000000-0000-0000-0000-000000000017");
  private static final Instant PREPARED_AT = Instant.parse("2026-08-11T02:00:00Z");
  private static final String LOCAL_PROPOSAL = "{\"schemaVersion\":\"2\"}";

  @Autowired private DataSource dataSource;

  private String isolatedSchema;

  @AfterEach
  void removeIsolatedSchema() throws SQLException {
    if (isolatedSchema == null || !isolatedSchema.startsWith("v17_attempt_")) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema " + isolatedSchema + " cascade");
    }
  }

  @Test
  void v17BackfillsLegacyDispatchesWithoutInventingAttemptHistoryOrADefault() throws Exception {
    JdbcClient isolated = createSchemaAndMigrateThroughVersion16();
    seedMemo(isolated);
    UUID preparedRun = insertRun(isolated, "QUEUED", "PENDING", null, "prepared");
    UUID runningRun = insertRun(isolated, "RUNNING", "PENDING", null, "running");
    UUID finalizedRun =
        insertRun(isolated, "REVIEW_REQUIRED", "SUCCESS", PREPARED_AT.plusSeconds(4), "finalized");
    insertDispatch(isolated, preparedRun, "PREPARED", 0, false, null);
    insertDispatch(isolated, runningRun, "RUNNING", 2, false, null);
    insertDispatch(isolated, finalizedRun, "FINALIZED", 3, false, null);

    migrateToLatest();

    assertThat(readLegacyEvidence(isolated))
        .containsExactlyInAnyOrder(
            new LegacyEvidence(preparedRun, "PREPARED", 0, "none", "none", 0),
            new LegacyEvidence(runningRun, "RUNNING", 2, "none", "none", 0),
            new LegacyEvidence(finalizedRun, "FINALIZED", 3, "none", "none", 0));
    assertThat(count(isolated, "analysis_run_dispatch_attempts")).isZero();
    assertThat(
            isolated
                .sql(
                    "select count(*) from information_schema.columns "
                        + "where table_schema=:schema and table_name='analysis_run_dispatches' "
                        + "and column_name='attempt_history_version' and column_default is not null")
                .param("schema", isolatedSchema)
                .query(Long.class)
                .single())
        .isZero();
    assertRejectedBy(
        "fk_analysis_run_dispatch_attempt_owner_history",
        () -> insertPendingAttempt(isolated, preparedRun, 1, OWNER_ID));

    UUID newRun = insertRun(isolated, "QUEUED", "PENDING", null, "no-default");
    assertRejectedBy(
        "attempt_history_version",
        () -> insertDispatch(isolated, newRun, "PREPARED", 0, false, null));
  }

  @Test
  void v17StoresTruthfulPendingObservedSupersededAndFencedEvidence() throws Exception {
    JdbcClient isolated = createSchemaAndMigrateLatest();
    seedMemo(isolated);

    UUID pendingRun = insertRun(isolated, "RUNNING", "PENDING", null, "pending");
    insertDispatch(isolated, pendingRun, "RUNNING", 1, true, "gateway-attempt-v1");
    insertPendingAttempt(isolated, pendingRun, 1, OWNER_ID);

    UUID completedRun =
        insertRun(isolated, "REVIEW_REQUIRED", "SUCCESS", PREPARED_AT.plusSeconds(4), "completed");
    insertDispatch(isolated, completedRun, "FINALIZED", 2, true, "gateway-attempt-v1");
    insertProcessLostAttempt(isolated, completedRun, 1);
    insertObservedResultAttempt(
        isolated,
        completedRun,
        2,
        "OBSERVED",
        "APPLIED_TO_RUN",
        "NOT_APPLICABLE",
        null,
        null,
        null,
        "NOT_APPLICABLE",
        null,
        null);

    UUID recoveryRun = insertRun(isolated, "RUNNING", "PENDING", null, "interrupted");
    insertDispatch(isolated, recoveryRun, "RUNNING", 1, true, "gateway-attempt-v1");
    insertCallerInterruptedAttempt(isolated, recoveryRun, 1);

    UUID fencedRun = insertRun(isolated, "RUNNING", "PENDING", null, "fenced");
    insertDispatch(isolated, fencedRun, "RUNNING", 2, true, "gateway-attempt-v1");
    insertObservedResultAttempt(
        isolated,
        fencedRun,
        1,
        "SUPERSEDED",
        "FENCED_OUT",
        "NOT_REPORTED",
        null,
        null,
        null,
        "NOT_REPORTED",
        null,
        null);
    insertPendingAttempt(isolated, fencedRun, 2, OWNER_ID);

    UUID reportedRun =
        insertRun(isolated, "REVIEW_REQUIRED", "SUCCESS", PREPARED_AT.plusSeconds(4), "reported");
    insertDispatch(isolated, reportedRun, "FINALIZED", 1, true, "gateway-attempt-v1");
    insertObservedResultAttempt(
        isolated,
        reportedRun,
        1,
        "OBSERVED",
        "APPLIED_TO_RUN",
        "REPORTED",
        0L,
        0L,
        0L,
        "REPORTED",
        BigDecimal.ZERO,
        "USD");

    assertThat(readAttemptEvidence(isolated, completedRun, 1))
        .isEqualTo(
            new AttemptEvidence(
                "SUPERSEDED",
                "UNKNOWN",
                "PROCESS_LOST",
                "UNKNOWN",
                null,
                "SUPERSEDED",
                "UNKNOWN",
                null,
                "UNKNOWN",
                null,
                null,
                null,
                "UNKNOWN",
                null,
                null));
    assertThat(readAttemptEvidence(isolated, completedRun, 2))
        .isEqualTo(
            new AttemptEvidence(
                "OBSERVED",
                "STARTED",
                "RESULT",
                "OBSERVED",
                "SUCCESS",
                "APPLIED_TO_RUN",
                "MEASURED",
                12L,
                "NOT_APPLICABLE",
                null,
                null,
                null,
                "NOT_APPLICABLE",
                null,
                null));
    assertThat(readAttemptEvidence(isolated, reportedRun, 1).modelTotalTokens()).isZero();
    assertThat(readAttemptEvidence(isolated, reportedRun, 1).costAmount())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(count(isolated, "analysis_run_dispatch_attempts")).isEqualTo(7);
  }

  @Test
  void v17RejectsIdentityFenceAndUnnecessaryIndexViolations() throws Exception {
    JdbcClient isolated = createSchemaAndMigrateLatest();
    seedMemo(isolated);
    UUID runId = insertRun(isolated, "RUNNING", "PENDING", null, "identity");
    insertDispatch(isolated, runId, "RUNNING", 1, true, "gateway-attempt-v1");
    insertPendingAttempt(isolated, runId, 1, OWNER_ID);

    assertRejectedBy(
        "uq_analysis_run_dispatch_attempts_in_flight",
        () -> insertPendingAttempt(isolated, runId, 2, OWNER_ID));
    assertRejectedBy(
        "pk_analysis_run_dispatch_attempts",
        () -> insertPendingAttempt(isolated, runId, 1, OWNER_ID));
    UUID ownerCheckRun = insertRun(isolated, "RUNNING", "PENDING", null, "owner-check");
    insertDispatch(isolated, ownerCheckRun, "RUNNING", 1, true, "gateway-attempt-v1");
    assertRejectedBy(
        "fk_analysis_run_dispatch_attempt_owner_history",
        () -> insertPendingAttempt(isolated, ownerCheckRun, 1, UUID.randomUUID()));
    assertRejectedBy(
        "fk_analysis_run_dispatch_attempt_owner_history",
        () -> insertPendingAttempt(isolated, UUID.randomUUID(), 1, OWNER_ID));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_fence",
        () -> insertPendingAttempt(isolated, runId, 0, OWNER_ID));

    assertRejectedBy(
        "ck_analysis_run_dispatches_attempt_history_version",
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set attempt_history_version='future-v2' where analysis_run_id=:runId")
                .param("runId", runId)
                .update());
    assertRejectedBy(
        "fk_analysis_run_dispatch_attempt_owner_history",
        () ->
            isolated
                .sql(
                    "delete from "
                        + table("analysis_run_dispatches")
                        + " where analysis_run_id=:runId")
                .param("runId", runId)
                .update());

    assertThat(
            isolated
                .sql(
                    "select indexname from pg_indexes where schemaname=:schema "
                        + "and tablename='analysis_run_dispatch_attempts' order by indexname")
                .param("schema", isolatedSchema)
                .query(String.class)
                .list())
        .containsExactly(
            "pk_analysis_run_dispatch_attempts", "uq_analysis_run_dispatch_attempts_in_flight");
  }

  @Test
  void v17RejectsNullBypassesContradictoryLifecycleAndFabricatedMetrics() throws Exception {
    JdbcClient isolated = createSchemaAndMigrateLatest();
    seedMemo(isolated);
    UUID pendingRun = insertRun(isolated, "RUNNING", "PENDING", null, "constraints-pending");
    insertDispatch(isolated, pendingRun, "RUNNING", 1, true, "gateway-attempt-v1");
    insertPendingAttempt(isolated, pendingRun, 1, OWNER_ID);

    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_timeout",
        () -> updateAttempt(isolated, pendingRun, 1, "effective_timeout_ms", 0));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_timeout",
        () -> updateAttempt(isolated, pendingRun, 1, "effective_timeout_ms", 60001));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_lease",
        () -> updateAttempt(isolated, pendingRun, 1, "lease_expires_at", PREPARED_AT));
    assertRejected(() -> updateAttempt(isolated, pendingRun, 1, "attempt_state", "OBSERVED"));
    assertRejected(() -> updateAttempt(isolated, pendingRun, 1, "result_state", null));
    assertRejected(() -> updateAttempt(isolated, pendingRun, 1, "gateway_outcome", "SUCCESS"));
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatch_attempts")
                        + " set result_state='OBSERVED', gateway_outcome='SUCCESS' "
                        + "where analysis_run_id=:runId and fence_token=1")
                .param("runId", pendingRun)
                .update());
    assertRejected(() -> updateAttempt(isolated, pendingRun, 1, "execution_state", "STARTED"));
    assertRejected(() -> updateAttempt(isolated, pendingRun, 1, "observed_at", PREPARED_AT));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_duration",
        () -> updateAttempt(isolated, pendingRun, 1, "duration_ms", 0L));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_model_tokens",
        () -> updateAttempt(isolated, pendingRun, 1, "model_input_tokens", 0L));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_cost",
        () -> updateAttempt(isolated, pendingRun, 1, "cost_amount", BigDecimal.ZERO));

    UUID fakeRun =
        insertRun(isolated, "REVIEW_REQUIRED", "SUCCESS", PREPARED_AT.plusSeconds(4), "fake");
    insertDispatch(isolated, fakeRun, "FINALIZED", 1, true, "gateway-attempt-v1");
    insertObservedResultAttempt(
        isolated,
        fakeRun,
        1,
        "OBSERVED",
        "APPLIED_TO_RUN",
        "NOT_APPLICABLE",
        null,
        null,
        null,
        "NOT_APPLICABLE",
        null,
        null);

    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_model_tokens",
        () -> updateAttempt(isolated, fakeRun, 1, "model_input_tokens", 0L));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_model_tokens",
        () -> updateAttempt(isolated, fakeRun, 1, "model_total_tokens", 0L));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_cost",
        () -> updateAttempt(isolated, fakeRun, 1, "cost_amount", BigDecimal.ZERO));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_duration",
        () -> updateAttempt(isolated, fakeRun, 1, "duration_ms", -1L));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_result",
        () -> updateAttempt(isolated, fakeRun, 1, "gateway_outcome", null));
    assertRejected(() -> updateAttempt(isolated, fakeRun, 1, "local_termination", null));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_execution_truth",
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatch_attempts")
                        + " set execution_state='STARTED', "
                        + "local_termination='EXECUTOR_REJECTED', result_state='UNKNOWN', "
                        + "gateway_outcome=null where analysis_run_id=:runId and fence_token=1")
                .param("runId", fakeRun)
                .update());
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_execution_truth",
        () -> updateAttempt(isolated, fakeRun, 1, "execution_state", "UNKNOWN"));

    assertThat(updateAttempt(isolated, fakeRun, 1, "duration_ms", Long.MAX_VALUE)).isOne();
    assertThat(updateAttempt(isolated, fakeRun, 1, "observed_at", PREPARED_AT.minusSeconds(3600)))
        .isOne();

    UUID reportedRun =
        insertRun(
            isolated, "REVIEW_REQUIRED", "SUCCESS", PREPARED_AT.plusSeconds(4), "reported-invalid");
    insertDispatch(isolated, reportedRun, "FINALIZED", 1, true, "gateway-attempt-v1");
    insertObservedResultAttempt(
        isolated,
        reportedRun,
        1,
        "OBSERVED",
        "APPLIED_TO_RUN",
        "REPORTED",
        4L,
        5L,
        9L,
        "REPORTED",
        new BigDecimal("0.01000000"),
        "USD");

    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_model_tokens",
        () -> updateAttempt(isolated, reportedRun, 1, "model_total_tokens", 8L));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_model_tokens",
        () -> updateAttempt(isolated, reportedRun, 1, "model_total_tokens", 1000000001L));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_model_tokens",
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatch_attempts")
                        + " set model_input_tokens=:maximum, model_output_tokens=:maximum, "
                        + "model_total_tokens=:maximum where analysis_run_id=:runId and fence_token=1")
                .param("maximum", Long.MAX_VALUE)
                .param("runId", reportedRun)
                .update());
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_cost",
        () -> updateAttempt(isolated, reportedRun, 1, "cost_currency", "usd"));
    assertRejectedBy(
        "ck_analysis_run_dispatch_attempt_cost",
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatch_attempts")
                        + " set cost_amount='NaN'::numeric where analysis_run_id=:runId "
                        + "and fence_token=1")
                .param("runId", reportedRun)
                .update());
  }

  private JdbcClient createSchemaAndMigrateThroughVersion16() throws SQLException {
    createIsolatedSchema();
    migrate(MigrationVersion.fromVersion("16"));
    return JdbcClient.create(dataSource);
  }

  private JdbcClient createSchemaAndMigrateLatest() throws SQLException {
    createIsolatedSchema();
    migrate(null);
    return JdbcClient.create(dataSource);
  }

  private void createIsolatedSchema() throws SQLException {
    isolatedSchema = "v17_attempt_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + isolatedSchema);
    }
  }

  private void migrateToLatest() {
    migrate(null);
  }

  private void migrate(MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(isolatedSchema)
            .defaultSchema(isolatedSchema)
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private void seedMemo(JdbcClient isolated) {
    Timestamp now = Timestamp.from(PREPARED_AT);
    isolated
        .sql(
            "insert into "
                + table("memos")
                + "(id,owner_id,current_revision,status,pinned,created_at,updated_at) "
                + "values(:id,:owner,1,'ACTIVE',false,:now,:now)")
        .param("id", MEMO_ID)
        .param("owner", OWNER_ID)
        .param("now", now)
        .update();
    isolated
        .sql(
            "insert into "
                + table("memo_revisions")
                + "(memo_id,owner_id,revision,content,content_hash,created_at,created_by,"
                + "client_recorded_at,source_time_zone) "
                + "values(:memo,:owner,1,'v17 fixture',:hash,:now,:owner,:now,'Asia/Seoul')")
        .param("memo", MEMO_ID)
        .param("owner", OWNER_ID)
        .param("hash", Hashing.sha256("v17 fixture"))
        .param("now", now)
        .update();
  }

  private UUID insertRun(
      JdbcClient isolated, String status, String outcome, Instant completedAt, String tokenSuffix) {
    UUID runId = UUID.randomUUID();
    isolated
        .sql(
            "insert into "
                + table("analysis_runs")
                + "(id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,"
                + "prompt_version,local_model_version,embedding_model_version,routing_policy_version,"
                + "cloud_transfer_mode,cloud_gateway_version,cloud_provider_id,cloud_model_version,"
                + "cloud_consent_policy_version,cloud_outcome,cloud_execution_contract_version,"
                + "cloud_provider_request_token,ambiguity_reasons,created_at,completed_at) "
                + "values(:id,:owner,:memo,1,'HYBRID',:status,'2','v17-analyzer','none','none','none',"
                + "'v17-policy','NO_NETWORK','v17-gateway','fake','none','no-network-v1',:outcome,"
                + "'durable-v1',:token,'[]',:createdAt,:completedAt)")
        .param("id", runId)
        .param("owner", OWNER_ID)
        .param("memo", MEMO_ID)
        .param("status", status)
        .param("outcome", outcome)
        .param("token", "pmr1_" + Hashing.sha256(tokenSuffix + runId))
        .param("createdAt", Timestamp.from(PREPARED_AT))
        .param("completedAt", completedAt == null ? null : Timestamp.from(completedAt))
        .update();
    return runId;
  }

  private void insertDispatch(
      JdbcClient isolated,
      UUID runId,
      String state,
      long fenceToken,
      boolean v17ColumnsExist,
      String attemptHistoryVersion) {
    boolean attempted = fenceToken > 0;
    Instant attemptAt = attempted ? PREPARED_AT.plusSeconds(1) : null;
    Instant leaseAt = "RUNNING".equals(state) ? PREPARED_AT.plusSeconds(3) : null;
    Instant finalizedAt = "FINALIZED".equals(state) ? PREPARED_AT.plusSeconds(4) : null;
    Instant updatedAt =
        finalizedAt != null ? finalizedAt : attemptAt != null ? attemptAt : PREPARED_AT;
    String historyColumn = v17ColumnsExist ? ",attempt_history_version" : "";
    String historyValue = v17ColumnsExist ? ",:attemptHistoryVersion" : "";
    var statement =
        isolated
            .sql(
                "insert into "
                    + table("analysis_run_dispatches")
                    + "(analysis_run_id,owner_id,reserved_proposal_id,idempotency_key_hash,request_hash,"
                    + "validated_local_proposal,validated_local_proposal_hash,executor_binding_id,"
                    + "call_timeout_ms,max_attempts,deadline_at,state,fence_token,last_attempt_started_at,"
                    + "lease_expires_at,prepared_at,finalized_at,updated_at,retrieval_context,"
                    + "retrieval_context_hash,retrieval_context_version,retrieval_context_candidate_count"
                    + historyColumn
                    + ") values(:runId,:owner,:proposalId,:keyHash,:requestHash,:proposal,:proposalHash,"
                    + ":bindingId,1000,3,:deadline,:state,:fence,:attemptAt,:leaseAt,:preparedAt,"
                    + ":finalizedAt,:updatedAt,null,null,'none',0"
                    + historyValue
                    + ")")
            .param("runId", runId)
            .param("owner", OWNER_ID)
            .param("proposalId", UUID.randomUUID())
            .param("keyHash", Hashing.sha256("key-" + runId))
            .param("requestHash", Hashing.sha256("request-" + runId))
            .param("proposal", "FINALIZED".equals(state) ? null : LOCAL_PROPOSAL)
            .param("proposalHash", Hashing.sha256(LOCAL_PROPOSAL))
            .param("bindingId", "cgb1_" + Hashing.sha256("binding-" + runId))
            .param("deadline", Timestamp.from(PREPARED_AT.plusSeconds(10)))
            .param("state", state)
            .param("fence", fenceToken)
            .param("attemptAt", attemptAt == null ? null : Timestamp.from(attemptAt))
            .param("leaseAt", leaseAt == null ? null : Timestamp.from(leaseAt))
            .param("preparedAt", Timestamp.from(PREPARED_AT))
            .param("finalizedAt", finalizedAt == null ? null : Timestamp.from(finalizedAt))
            .param("updatedAt", Timestamp.from(updatedAt));
    if (v17ColumnsExist) {
      statement.param("attemptHistoryVersion", attemptHistoryVersion);
    }
    statement.update();
  }

  private void insertPendingAttempt(
      JdbcClient isolated, UUID runId, long fenceToken, UUID ownerId) {
    isolated
        .sql(
            "insert into "
                + table("analysis_run_dispatch_attempts")
                + "(analysis_run_id,owner_id,attempt_history_version,fence_token,"
                + "effective_timeout_ms,attempt_state,"
                + "execution_state,local_termination,result_state,gateway_outcome,disposition,"
                + "duration_status,duration_ms,model_token_status,model_input_tokens,"
                + "model_output_tokens,model_total_tokens,cost_status,cost_amount,cost_currency,"
                + "claimed_at,lease_expires_at,observed_at,updated_at) "
                + "values(:runId,:owner,'gateway-attempt-v1',:fence,1000,'IN_FLIGHT','PENDING',"
                + "null,'PENDING',null,"
                + "'PENDING','UNKNOWN',null,'PENDING',null,null,null,'PENDING',null,null,:claimedAt,"
                + ":leaseAt,null,:updatedAt)")
        .param("runId", runId)
        .param("owner", ownerId)
        .param("fence", fenceToken)
        .param("claimedAt", Timestamp.from(PREPARED_AT))
        .param("leaseAt", Timestamp.from(PREPARED_AT.plusSeconds(2)))
        .param("updatedAt", Timestamp.from(PREPARED_AT))
        .update();
  }

  private void insertProcessLostAttempt(JdbcClient isolated, UUID runId, long fenceToken) {
    isolated
        .sql(
            "insert into "
                + table("analysis_run_dispatch_attempts")
                + "(analysis_run_id,owner_id,attempt_history_version,fence_token,"
                + "effective_timeout_ms,attempt_state,"
                + "execution_state,local_termination,result_state,gateway_outcome,disposition,"
                + "duration_status,duration_ms,model_token_status,model_input_tokens,"
                + "model_output_tokens,model_total_tokens,cost_status,cost_amount,cost_currency,"
                + "claimed_at,lease_expires_at,observed_at,updated_at) "
                + "values(:runId,:owner,'gateway-attempt-v1',:fence,1000,'SUPERSEDED','UNKNOWN',"
                + "'PROCESS_LOST','UNKNOWN',"
                + "null,'SUPERSEDED','UNKNOWN',null,'UNKNOWN',null,null,null,'UNKNOWN',null,null,"
                + ":claimedAt,:leaseAt,null,:updatedAt)")
        .param("runId", runId)
        .param("owner", OWNER_ID)
        .param("fence", fenceToken)
        .param("claimedAt", Timestamp.from(PREPARED_AT))
        .param("leaseAt", Timestamp.from(PREPARED_AT.plusSeconds(2)))
        .param("updatedAt", Timestamp.from(PREPARED_AT.plusSeconds(3)))
        .update();
  }

  private void insertCallerInterruptedAttempt(JdbcClient isolated, UUID runId, long fenceToken) {
    isolated
        .sql(
            "insert into "
                + table("analysis_run_dispatch_attempts")
                + "(analysis_run_id,owner_id,attempt_history_version,fence_token,"
                + "effective_timeout_ms,attempt_state,"
                + "execution_state,local_termination,result_state,gateway_outcome,disposition,"
                + "duration_status,duration_ms,model_token_status,model_input_tokens,"
                + "model_output_tokens,model_total_tokens,cost_status,cost_amount,cost_currency,"
                + "claimed_at,lease_expires_at,observed_at,updated_at) "
                + "values(:runId,:owner,'gateway-attempt-v1',:fence,1000,'IN_FLIGHT','STARTED',"
                + "'CALLER_INTERRUPTED',"
                + "'UNKNOWN',null,'RECOVERY_PENDING','MEASURED',7,'UNKNOWN',null,null,null,'UNKNOWN',"
                + "null,null,:claimedAt,:leaseAt,:observedAt,:updatedAt)")
        .param("runId", runId)
        .param("owner", OWNER_ID)
        .param("fence", fenceToken)
        .param("claimedAt", Timestamp.from(PREPARED_AT))
        .param("leaseAt", Timestamp.from(PREPARED_AT.plusSeconds(2)))
        .param("observedAt", Timestamp.from(PREPARED_AT.plusSeconds(1)))
        .param("updatedAt", Timestamp.from(PREPARED_AT.plusSeconds(1)))
        .update();
  }

  private void insertObservedResultAttempt(
      JdbcClient isolated,
      UUID runId,
      long fenceToken,
      String attemptState,
      String disposition,
      String modelTokenStatus,
      Long modelInputTokens,
      Long modelOutputTokens,
      Long modelTotalTokens,
      String costStatus,
      BigDecimal costAmount,
      String costCurrency) {
    isolated
        .sql(
            "insert into "
                + table("analysis_run_dispatch_attempts")
                + "(analysis_run_id,owner_id,attempt_history_version,fence_token,"
                + "effective_timeout_ms,attempt_state,"
                + "execution_state,local_termination,result_state,gateway_outcome,disposition,"
                + "duration_status,duration_ms,model_token_status,model_input_tokens,"
                + "model_output_tokens,model_total_tokens,cost_status,cost_amount,cost_currency,"
                + "claimed_at,lease_expires_at,observed_at,updated_at) "
                + "values(:runId,:owner,'gateway-attempt-v1',:fence,1000,:attemptState,'STARTED',"
                + "'RESULT','OBSERVED',"
                + "'SUCCESS',:disposition,'MEASURED',12,:modelTokenStatus,:modelInputTokens,"
                + ":modelOutputTokens,:modelTotalTokens,:costStatus,:costAmount,:costCurrency,"
                + ":claimedAt,:leaseAt,:observedAt,:updatedAt)")
        .param("runId", runId)
        .param("owner", OWNER_ID)
        .param("fence", fenceToken)
        .param("attemptState", attemptState)
        .param("disposition", disposition)
        .param("modelTokenStatus", modelTokenStatus)
        .param("modelInputTokens", modelInputTokens)
        .param("modelOutputTokens", modelOutputTokens)
        .param("modelTotalTokens", modelTotalTokens)
        .param("costStatus", costStatus)
        .param("costAmount", costAmount)
        .param("costCurrency", costCurrency)
        .param("claimedAt", Timestamp.from(PREPARED_AT))
        .param("leaseAt", Timestamp.from(PREPARED_AT.plusSeconds(2)))
        .param("observedAt", Timestamp.from(PREPARED_AT.plusSeconds(1)))
        .param("updatedAt", Timestamp.from(PREPARED_AT.plusSeconds(1)))
        .update();
  }

  private List<LegacyEvidence> readLegacyEvidence(JdbcClient isolated) {
    return isolated
        .sql(
            "select analysis_run_id,state,fence_token,attempt_history_version,"
                + "retrieval_context_version,retrieval_context_candidate_count from "
                + table("analysis_run_dispatches"))
        .query(
            (resultSet, rowNumber) ->
                new LegacyEvidence(
                    resultSet.getObject("analysis_run_id", UUID.class),
                    resultSet.getString("state"),
                    resultSet.getLong("fence_token"),
                    resultSet.getString("attempt_history_version"),
                    resultSet.getString("retrieval_context_version"),
                    resultSet.getInt("retrieval_context_candidate_count")))
        .list();
  }

  private AttemptEvidence readAttemptEvidence(JdbcClient isolated, UUID runId, long fenceToken) {
    return isolated
        .sql(
            "select attempt_state,execution_state,local_termination,result_state,gateway_outcome,"
                + "disposition,duration_status,duration_ms,model_token_status,model_input_tokens,"
                + "model_output_tokens,model_total_tokens,cost_status,cost_amount,cost_currency from "
                + table("analysis_run_dispatch_attempts")
                + " where analysis_run_id=:runId and fence_token=:fence")
        .param("runId", runId)
        .param("fence", fenceToken)
        .query(
            (resultSet, rowNumber) ->
                new AttemptEvidence(
                    resultSet.getString("attempt_state"),
                    resultSet.getString("execution_state"),
                    resultSet.getString("local_termination"),
                    resultSet.getString("result_state"),
                    resultSet.getString("gateway_outcome"),
                    resultSet.getString("disposition"),
                    resultSet.getString("duration_status"),
                    nullableLong(resultSet, "duration_ms"),
                    resultSet.getString("model_token_status"),
                    nullableLong(resultSet, "model_input_tokens"),
                    nullableLong(resultSet, "model_output_tokens"),
                    nullableLong(resultSet, "model_total_tokens"),
                    resultSet.getString("cost_status"),
                    resultSet.getBigDecimal("cost_amount"),
                    resultSet.getString("cost_currency")))
        .single();
  }

  private Long nullableLong(java.sql.ResultSet resultSet, String column) throws SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  private int updateAttempt(
      JdbcClient isolated, UUID runId, long fenceToken, String column, Object value) {
    Object parameter = value instanceof Instant instant ? Timestamp.from(instant) : value;
    return isolated
        .sql(
            "update "
                + table("analysis_run_dispatch_attempts")
                + " set "
                + column
                + "=:value where analysis_run_id=:runId and fence_token=:fence")
        .param("value", parameter)
        .param("runId", runId)
        .param("fence", fenceToken)
        .update();
  }

  private long count(JdbcClient isolated, String tableName) {
    return isolated.sql("select count(*) from " + table(tableName)).query(Long.class).single();
  }

  private void assertRejected(Runnable mutation) {
    assertThatThrownBy(mutation::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private void assertRejectedBy(String constraint, Runnable mutation) {
    Throwable thrown = catchThrowable(mutation::run);
    assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    Throwable rootCause = thrown;
    while (rootCause.getCause() != null) {
      rootCause = rootCause.getCause();
    }
    assertThat(rootCause).hasMessageContaining(constraint);
  }

  private String table(String tableName) {
    return isolatedSchema + "." + tableName;
  }

  private record LegacyEvidence(
      UUID runId,
      String state,
      long fenceToken,
      String attemptHistoryVersion,
      String retrievalContextVersion,
      int retrievalContextCandidateCount) {}

  private record AttemptEvidence(
      String attemptState,
      String executionState,
      String localTermination,
      String resultState,
      String gatewayOutcome,
      String disposition,
      String durationStatus,
      Long durationMs,
      String modelTokenStatus,
      Long modelInputTokens,
      Long modelOutputTokens,
      Long modelTotalTokens,
      String costStatus,
      BigDecimal costAmount,
      String costCurrency) {}
}
