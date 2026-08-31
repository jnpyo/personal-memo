package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
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
class LocalModelFallbackEvidenceMigrationIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID MEMO_ID = UUID.fromString("30000000-0000-0000-0000-000000000019");
  private static final Instant PREPARED_AT = Instant.parse("2026-08-21T01:00:00Z");
  private static final String LOCAL_PROPOSAL = "{\"schemaVersion\":\"2\"}";
  private static final String CURRENT_EVIDENCE =
      "{"
          + "\"version\":\"local-decision-v1\","
          + "\"typeSummary\":{\"candidateCount\":2,\"leader\":\"TASK\","
          + "\"leaderScore\":0.9,\"runnerUpScore\":0.8,\"margin\":0.1},"
          + "\"temporalSummary\":{\"candidateCount\":1,\"preciseCount\":1,"
          + "\"impreciseCount\":0,\"explicitTimeCount\":1},"
          + "\"taxonomySummary\":{\"candidateCount\":0,\"newProposalCount\":0,"
          + "\"strongestScore\":null},"
          + "\"itemSummary\":{\"candidateCount\":1,\"taskCount\":1,"
          + "\"verbPresentCount\":1,\"referentPresentCount\":1,\"dueBindingCount\":1},"
          + "\"relationCandidateCount\":0}";

  @Autowired private DataSource dataSource;

  private String isolatedSchema;

  @AfterEach
  void removeIsolatedSchema() throws SQLException {
    if (isolatedSchema == null || !isolatedSchema.startsWith("v19_fallback_")) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema " + isolatedSchema + " cascade");
    }
  }

  @Test
  void v19PreservesV18AbsenceAndDoesNotInventModelContribution() throws Exception {
    JdbcClient isolated = createSchemaAndMigrate(MigrationVersion.fromVersion("18"));
    seedMemo(isolated);
    UUID runId = insertRun(isolated, "NO_NETWORK", "1");
    insertLegacyPreparedDispatch(isolated, runId);

    migrate(null);

    LegacyEvidence evidence =
        isolated
            .sql(
                "select local_decision_evidence_version,local_decision_evidence,"
                    + "fallback_policy_version,fallback_reason_codes,model_contribution_status,"
                    + "model_changed_fields from "
                    + table("analysis_run_dispatches")
                    + " where analysis_run_id=:runId")
            .param("runId", runId)
            .query(
                (resultSet, rowNumber) ->
                    new LegacyEvidence(
                        resultSet.getString("local_decision_evidence_version"),
                        resultSet.getString("local_decision_evidence"),
                        resultSet.getString("fallback_policy_version"),
                        resultSet.getString("fallback_reason_codes"),
                        resultSet.getString("model_contribution_status"),
                        resultSet.getString("model_changed_fields")))
            .single();
    assertThat(evidence)
        .isEqualTo(new LegacyEvidence("none", null, "legacy-v0", "[]", "NOT_RECORDED", "[]"));

    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set fallback_policy_version='model-fallback-v1' "
                        + "where analysis_run_id=:runId")
                .param("runId", runId)
                .update());
  }

  @Test
  void v19RequiresBoundedRawFreeCurrentEvidenceAndCoherentContributionLifecycle() throws Exception {
    JdbcClient isolated = createSchemaAndMigrate(null);
    seedMemo(isolated);
    UUID runId = insertRun(isolated, "LOCAL_MACHINE_MEMO_CONTENT", "2");
    insertCurrentPreparedDispatch(isolated, runId);

    assertThat(
            isolated
                .sql(
                    "select fallback_reason_codes::text from "
                        + table("analysis_run_dispatches")
                        + " where analysis_run_id=:runId")
                .param("runId", runId)
                .query(String.class)
                .single())
        .isEqualTo("[\"LOW_TYPE_MARGIN\"]");

    assertRejected(
        () -> updateJson(isolated, runId, "fallback_reason_codes", "[\"private memo\"]"));
    assertRejected(
        () ->
            updateJson(
                isolated,
                runId,
                "fallback_reason_codes",
                "[\"LOW_TYPE_MARGIN\",\"LOW_TYPE_MARGIN\"]"));
    assertRejected(
        () -> updateJson(isolated, runId, "model_changed_fields", "[\"SUGGESTED_TITLE\"]"));
    assertRejected(
        () ->
            updateJson(
                isolated,
                runId,
                "local_decision_evidence",
                CURRENT_EVIDENCE.replace(
                    "\"relationCandidateCount\":0",
                    "\"relationCandidateCount\":0,\"title\":\"private memo\"")));
    assertRejected(
        () ->
            updateJson(
                isolated,
                runId,
                "local_decision_evidence",
                CURRENT_EVIDENCE.replace("\"leader\":\"TASK\"", "\"leader\":\"private\"")));
    assertRejected(
        () -> updateText(isolated, runId, "model_contribution_status", "ACCEPTED_CHANGED"));

    finalizeRunAndDispatch(isolated, runId, "SUCCESS", "ACCEPTED_CHANGED", "[\"SUGGESTED_TITLE\"]");

    assertThat(
            isolated
                .sql(
                    "select count(*) from "
                        + table("analysis_run_dispatches")
                        + " where analysis_run_id=:runId and state='FINALIZED' "
                        + "and validated_local_proposal is null "
                        + "and local_decision_evidence is not null")
                .param("runId", runId)
                .query(Long.class)
                .single())
        .isOne();

    assertRejected(() -> updateJson(isolated, runId, "model_changed_fields", "[]"));
    assertRejected(
        () -> updateText(isolated, runId, "model_contribution_status", "LOCAL_FALLBACK"));

    UUID unchangedRun = insertRun(isolated, "LOCAL_MACHINE_MEMO_CONTENT", "4");
    insertCurrentPreparedDispatch(isolated, unchangedRun);
    finalizeRunAndDispatch(isolated, unchangedRun, "SUCCESS", "ACCEPTED_UNCHANGED", "[]");

    UUID fallbackRun = insertRun(isolated, "LOCAL_MACHINE_MEMO_CONTENT", "5");
    insertCurrentPreparedDispatch(isolated, fallbackRun);
    finalizeRunAndDispatch(isolated, fallbackRun, "TIMEOUT", "LOCAL_FALLBACK", "[]");

    assertThat(
            isolated
                .sql(
                    "select model_contribution_status from "
                        + table("analysis_run_dispatches")
                        + " where analysis_run_id in (:unchangedRun,:fallbackRun) "
                        + "order by model_contribution_status")
                .param("unchangedRun", unchangedRun)
                .param("fallbackRun", fallbackRun)
                .query(String.class)
                .list())
        .containsExactly("ACCEPTED_UNCHANGED", "LOCAL_FALLBACK");
  }

  @Test
  void localMachineMemoContentIsDistinctFromNoNetworkAndNeedsNoExternalConsent() throws Exception {
    JdbcClient isolated = createSchemaAndMigrate(null);
    seedMemo(isolated);
    UUID runId = insertRun(isolated, "LOCAL_MACHINE_MEMO_CONTENT", "3");

    assertThat(
            isolated
                .sql(
                    "select cloud_transfer_mode from "
                        + table("analysis_runs")
                        + " where id=:runId")
                .param("runId", runId)
                .query(String.class)
                .single())
        .isEqualTo("LOCAL_MACHINE_MEMO_CONTENT");
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_runs")
                        + " set cloud_authorization_checked_at=:checkedAt where id=:runId")
                .param("checkedAt", Timestamp.from(PREPARED_AT))
                .param("runId", runId)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_runs")
                        + " set cloud_transfer_mode='NOT_REQUIRED' where id=:runId")
                .param("runId", runId)
                .update());
  }

  @Test
  void v20BackfillsLegacyInvocationAndNoApprovedCorrectionContext() throws Exception {
    JdbcClient isolated = createSchemaAndMigrate(MigrationVersion.fromVersion("19"));
    seedMemo(isolated);
    UUID runId = insertRun(isolated, "LOCAL_MACHINE_MEMO_CONTENT", "6");
    insertCurrentPreparedDispatch(isolated, runId);

    migrate(null);

    V20Evidence evidence =
        isolated
            .sql(
                "select invocation_policy_version,invocation_mode,invocation_reason_code,"
                    + "approved_correction_context,approved_correction_context_hash,"
                    + "approved_correction_context_version,approved_correction_context_count from "
                    + table("analysis_run_dispatches")
                    + " where analysis_run_id=:runId")
            .param("runId", runId)
            .query(
                (resultSet, rowNumber) ->
                    new V20Evidence(
                        resultSet.getString("invocation_policy_version"),
                        resultSet.getString("invocation_mode"),
                        resultSet.getString("invocation_reason_code"),
                        resultSet.getString("approved_correction_context"),
                        resultSet.getString("approved_correction_context_hash"),
                        resultSet.getString("approved_correction_context_version"),
                        resultSet.getInt("approved_correction_context_count")))
            .single();

    assertThat(evidence)
        .isEqualTo(
            new V20Evidence(
                "legacy-v0", "LEGACY_UNKNOWN", "LEGACY_UNKNOWN", null, null, "none", 0));
  }

  @Test
  void v20EnforcesAiPreferredReasonContextAndLifecycleCoherence() throws Exception {
    JdbcClient isolated = createSchemaAndMigrate(MigrationVersion.fromVersion("19"));
    seedMemo(isolated);
    UUID runId = insertRun(isolated, "LOCAL_MACHINE_MEMO_CONTENT", "7");
    insertCurrentPreparedDispatch(isolated, runId);
    migrate(null);

    String context = "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[]}";
    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set invocation_policy_version='model-invocation-v1',"
                + "invocation_mode='AI_PREFERRED',invocation_reason_code='AI_PREFERRED_POLICY',"
                + "approved_correction_context=:context,approved_correction_context_hash=:hash,"
                + "approved_correction_context_version='approved-type-anchor-k3-v1',"
                + "approved_correction_context_count=0,fallback_reason_codes='[]'::jsonb "
                + "where analysis_run_id=:runId")
        .param("context", context)
        .param("hash", Hashing.sha256(context))
        .param("runId", runId)
        .update();

    assertRejected(
        () -> updateText(isolated, runId, "invocation_reason_code", "SEMANTIC_UNCERTAINTY"));
    assertRejected(() -> updateNull(isolated, runId, "approved_correction_context_hash"));
    assertRejected(() -> updateNull(isolated, runId, "approved_correction_context"));
    assertRejected(
        () ->
            updateJson(
                isolated,
                runId,
                "local_decision_evidence",
                CURRENT_EVIDENCE.replace("\"version\":\"local-decision-v1\"", "\"version\":null")));
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set fallback_reason_codes='[\"LOW_TYPE_MARGIN\"]'::jsonb,"
                        + "invocation_mode='UNCERTAINTY_ONLY',"
                        + "invocation_reason_code='SEMANTIC_UNCERTAINTY' "
                        + "where analysis_run_id=:runId")
                .param("runId", runId)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set state='FINALIZED',validated_local_proposal=null,"
                        + "lease_expires_at=null,finalized_at=:finalizedAt,updated_at=:finalizedAt,"
                        + "model_contribution_status='ACCEPTED_UNCHANGED',"
                        + "model_changed_fields='[]'::jsonb where analysis_run_id=:runId")
                .param("finalizedAt", Timestamp.from(PREPARED_AT.plusSeconds(3)))
                .param("runId", runId)
                .update());

    finalizeRunAndDispatch(isolated, runId, "SUCCESS", "ACCEPTED_UNCHANGED", "[]");

    assertThat(
            isolated
                .sql(
                    "select count(*) from "
                        + table("analysis_run_dispatches")
                        + " where analysis_run_id=:runId and state='FINALIZED' "
                        + "and approved_correction_context is null "
                        + "and approved_correction_context_hash=:hash "
                        + "and approved_correction_context_version='approved-type-anchor-k3-v1' "
                        + "and approved_correction_context_count=0")
                .param("runId", runId)
                .param("hash", Hashing.sha256(context))
                .query(Long.class)
                .single())
        .isOne();
  }

  private JdbcClient createSchemaAndMigrate(MigrationVersion target) throws SQLException {
    isolatedSchema = "v19_fallback_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + isolatedSchema);
    }
    migrate(target);
    return JdbcClient.create(dataSource);
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
                + "values(:memo,:owner,1,'synthetic v19 fixture',:hash,:now,:owner,:now,'Asia/Seoul')")
        .param("memo", MEMO_ID)
        .param("owner", OWNER_ID)
        .param("hash", Hashing.sha256("synthetic v19 fixture"))
        .param("now", now)
        .update();
  }

  private UUID insertRun(JdbcClient isolated, String transferMode, String tokenCharacter) {
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
                + "values(:id,:owner,:memo,1,'HYBRID','QUEUED','2','v19-analyzer','none','none','none',"
                + "'model-fallback-v1',:transferMode,'v19-gateway','local-ollama','liquidai-local',"
                + "'local-machine-v1','PENDING','durable-v1',:token,'[]',:createdAt,null)")
        .param("id", runId)
        .param("owner", OWNER_ID)
        .param("memo", MEMO_ID)
        .param("transferMode", transferMode)
        .param("token", "pmr1_" + tokenCharacter.repeat(64))
        .param("createdAt", Timestamp.from(PREPARED_AT))
        .update();
    return runId;
  }

  private void insertLegacyPreparedDispatch(JdbcClient isolated, UUID runId) {
    isolated
        .sql(baseDispatchInsert("", "", "", ""))
        .param("runId", runId)
        .param("owner", OWNER_ID)
        .param("proposalId", UUID.randomUUID())
        .param("keyHash", Hashing.sha256("key-" + runId))
        .param("requestHash", Hashing.sha256("request-" + runId))
        .param("proposal", LOCAL_PROPOSAL)
        .param("proposalHash", Hashing.sha256(LOCAL_PROPOSAL))
        .param("bindingId", "cgb1_" + Hashing.sha256("binding-" + runId))
        .param("deadline", Timestamp.from(PREPARED_AT.plusSeconds(10)))
        .param("preparedAt", Timestamp.from(PREPARED_AT))
        .update();
  }

  private void insertCurrentPreparedDispatch(JdbcClient isolated, UUID runId) {
    isolated
        .sql(
            baseDispatchInsert(
                ",local_decision_evidence_version,local_decision_evidence,fallback_policy_version,"
                    + "fallback_reason_codes,model_contribution_status,model_changed_fields",
                ",'local-decision-v1',cast(:evidence as jsonb),'model-fallback-v1',"
                    + "'[\"LOW_TYPE_MARGIN\"]'::jsonb,'PENDING','[]'::jsonb",
                "",
                ""))
        .param("runId", runId)
        .param("owner", OWNER_ID)
        .param("proposalId", UUID.randomUUID())
        .param("keyHash", Hashing.sha256("key-" + runId))
        .param("requestHash", Hashing.sha256("request-" + runId))
        .param("proposal", LOCAL_PROPOSAL)
        .param("proposalHash", Hashing.sha256(LOCAL_PROPOSAL))
        .param("bindingId", "cgb1_" + Hashing.sha256("binding-" + runId))
        .param("deadline", Timestamp.from(PREPARED_AT.plusSeconds(10)))
        .param("preparedAt", Timestamp.from(PREPARED_AT))
        .param("evidence", CURRENT_EVIDENCE)
        .update();
  }

  private String baseDispatchInsert(
      String evidenceColumns, String evidenceValues, String ignoredColumns, String ignoredValues) {
    return "insert into "
        + table("analysis_run_dispatches")
        + "(analysis_run_id,owner_id,reserved_proposal_id,idempotency_key_hash,request_hash,"
        + "validated_local_proposal,validated_local_proposal_hash,executor_binding_id,"
        + "call_timeout_ms,max_attempts,deadline_at,state,fence_token,last_attempt_started_at,"
        + "lease_expires_at,prepared_at,finalized_at,updated_at,retrieval_context,"
        + "retrieval_context_hash,retrieval_context_version,retrieval_context_candidate_count,"
        + "attempt_history_version"
        + evidenceColumns
        + ") values(:runId,:owner,:proposalId,:keyHash,:requestHash,:proposal,:proposalHash,"
        + ":bindingId,1000,3,:deadline,'PREPARED',0,null,null,:preparedAt,null,:preparedAt,"
        + "null,null,'none',0,'none'"
        + evidenceValues
        + ")";
  }

  private void updateJson(JdbcClient isolated, UUID runId, String column, String value) {
    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set "
                + column
                + "=cast(:value as jsonb) where analysis_run_id=:runId")
        .param("value", value)
        .param("runId", runId)
        .update();
  }

  private void updateText(JdbcClient isolated, UUID runId, String column, String value) {
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

  private void updateNull(JdbcClient isolated, UUID runId, String column) {
    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set "
                + column
                + "=null where analysis_run_id=:runId")
        .param("runId", runId)
        .update();
  }

  private void finalizeRunAndDispatch(
      JdbcClient isolated,
      UUID runId,
      String cloudOutcome,
      String contributionStatus,
      String changedFields) {
    Instant finalizedAt = PREPARED_AT.plusSeconds(3);
    isolated
        .sql(
            "update "
                + table("analysis_runs")
                + " set status='REVIEW_REQUIRED',cloud_outcome=:cloudOutcome,"
                + "completed_at=:completedAt where id=:runId")
        .param("cloudOutcome", cloudOutcome)
        .param("completedAt", Timestamp.from(finalizedAt))
        .param("runId", runId)
        .update();
    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set state='FINALIZED',validated_local_proposal=null,lease_expires_at=null,"
                + "approved_correction_context=null,"
                + "finalized_at=:finalizedAt,updated_at=:finalizedAt,"
                + "model_contribution_status=:contributionStatus,"
                + "model_changed_fields=cast(:changedFields as jsonb) "
                + "where analysis_run_id=:runId")
        .param("finalizedAt", Timestamp.from(finalizedAt))
        .param("contributionStatus", contributionStatus)
        .param("changedFields", changedFields)
        .param("runId", runId)
        .update();
  }

  private void assertRejected(Runnable mutation) {
    assertThatThrownBy(mutation::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private String table(String tableName) {
    return isolatedSchema + "." + tableName;
  }

  private record LegacyEvidence(
      String evidenceVersion,
      String evidence,
      String policyVersion,
      String reasonCodes,
      String contributionStatus,
      String changedFields) {}

  private record V20Evidence(
      String invocationPolicyVersion,
      String invocationMode,
      String invocationReasonCode,
      String approvedCorrectionContext,
      String approvedCorrectionContextHash,
      String approvedCorrectionContextVersion,
      int approvedCorrectionContextCount) {}
}
