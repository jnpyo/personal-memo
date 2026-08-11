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
import local.personalmemo.analysis.domain.TagRetrievalContext;
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
class BoundedTagRetrievalContextMigrationIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID MEMO_ID = UUID.fromString("30000000-0000-0000-0000-000000000016");
  private static final Instant PREPARED_AT = Instant.parse("2026-08-11T01:00:00Z");
  private static final String LOCAL_PROPOSAL = "{\"schemaVersion\":\"2\"}";
  private static final String CURRENT_CONTEXT =
      "{\"version\":\"tag-alias-exact-k8-v1\",\"candidates\":["
          + "{\"rank\":1,\"existingTagId\":\"10000000-0000-0000-0000-000000000001\","
          + "\"canonicalName\":\"운영체제\",\"matchedAlias\":\"OS\",\"matchKind\":\"ALIAS\","
          + "\"sourceCandidateIndex\":0}]}";

  @Autowired private DataSource dataSource;

  private String isolatedSchema;

  @AfterEach
  void removeIsolatedSchema() throws SQLException {
    if (isolatedSchema == null || !isolatedSchema.startsWith("v16_context_")) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema " + isolatedSchema + " cascade");
    }
  }

  @Test
  void v16PreservesPreparedRunningAndFinalizedV15RowsWithoutInventingContext() throws Exception {
    JdbcClient isolated = createSchemaAndMigrateThroughVersion15();
    seedMemo(isolated);
    UUID preparedRun = insertRun(isolated, "QUEUED", "PENDING", null, "1");
    UUID runningRun = insertRun(isolated, "RUNNING", "PENDING", null, "2");
    UUID finalizedRun =
        insertRun(isolated, "REVIEW_REQUIRED", "SUCCESS", PREPARED_AT.plusSeconds(3), "3");
    insertDispatch(isolated, preparedRun, "PREPARED", false, false, LegacyContext.none());
    insertDispatch(isolated, runningRun, "RUNNING", false, false, LegacyContext.none());
    insertDispatch(isolated, finalizedRun, "FINALIZED", true, false, LegacyContext.none());

    migrateToLatest();

    assertThat(readContextEvidence(isolated))
        .containsExactlyInAnyOrder(
            new ContextEvidence(preparedRun, "PREPARED", "none", 0, null, null),
            new ContextEvidence(runningRun, "RUNNING", "none", 0, null, null),
            new ContextEvidence(finalizedRun, "FINALIZED", "none", 0, null, null));
  }

  @Test
  void v16AllowsCurrentPreparedRunningAndScrubbedFinalizedSnapshots() throws Exception {
    JdbcClient isolated = createSchemaAndMigrateLatest();
    seedMemo(isolated);
    UUID preparedRun = insertRun(isolated, "QUEUED", "PENDING", null, "4");
    UUID runningRun = insertRun(isolated, "RUNNING", "PENDING", null, "5");
    UUID finalizedRun =
        insertRun(isolated, "REVIEW_REQUIRED", "SUCCESS", PREPARED_AT.plusSeconds(3), "6");
    LegacyContext current = LegacyContext.current();

    insertDispatch(isolated, preparedRun, "PREPARED", false, true, current);
    insertDispatch(isolated, runningRun, "RUNNING", false, true, current);
    insertDispatch(isolated, finalizedRun, "FINALIZED", true, true, current);

    assertThat(readContextEvidence(isolated))
        .containsExactlyInAnyOrder(
            new ContextEvidence(
                preparedRun,
                "PREPARED",
                TagRetrievalContext.CURRENT_VERSION,
                1,
                CURRENT_CONTEXT,
                Hashing.sha256(CURRENT_CONTEXT)),
            new ContextEvidence(
                runningRun,
                "RUNNING",
                TagRetrievalContext.CURRENT_VERSION,
                1,
                CURRENT_CONTEXT,
                Hashing.sha256(CURRENT_CONTEXT)),
            new ContextEvidence(
                finalizedRun,
                "FINALIZED",
                TagRetrievalContext.CURRENT_VERSION,
                1,
                null,
                Hashing.sha256(CURRENT_CONTEXT)));
  }

  @Test
  void v16RejectsMalformedEvidenceAndRequiresRawContextOnlyWhileRecoverable() throws Exception {
    JdbcClient isolated = createSchemaAndMigrateLatest();
    seedMemo(isolated);
    UUID runId = insertRun(isolated, "QUEUED", "PENDING", null, "7");
    insertDispatch(isolated, runId, "PREPARED", false, true, LegacyContext.current());

    assertRejected(() -> updateText(isolated, runId, "retrieval_context", "not-json"));
    assertRejected(() -> updateText(isolated, runId, "retrieval_context_hash", "bad"));
    assertRejected(() -> updateInteger(isolated, runId, "retrieval_context_candidate_count", 2));
    assertRejected(() -> updateInteger(isolated, runId, "retrieval_context_candidate_count", 9));
    assertRejected(() -> updateText(isolated, runId, "retrieval_context_version", "future-v2"));
    assertRejected(() -> updateText(isolated, runId, "retrieval_context_version", "none"));
    assertRejected(() -> updateText(isolated, runId, "retrieval_context", null));
    assertRejected(() -> updateText(isolated, runId, "retrieval_context_hash", null));
    assertRejected(
        () ->
            updateText(
                isolated,
                runId,
                "retrieval_context",
                CURRENT_CONTEXT.replace(TagRetrievalContext.CURRENT_VERSION, "future-v2")));
    assertRejected(
        () ->
            updateText(
                isolated,
                runId,
                "retrieval_context",
                "{\"padding\":\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\",\"candidates\":[{}]}"));
    assertRejected(
        () ->
            updateText(
                isolated,
                runId,
                "retrieval_context",
                "{\"version\":null,\"padding\":\"xxxxxxxxxxxxxxxxxxxxxxxx\","
                    + "\"candidates\":[{}]}"));
    assertRejected(
        () ->
            updateText(
                isolated,
                runId,
                "retrieval_context",
                "{\"version\":\"tag-alias-exact-k8-v1\",\"candidates\":{}}"));

    Instant finalizedAt = PREPARED_AT.plusSeconds(3);
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set state='FINALIZED', validated_local_proposal=null, "
                        + "lease_expires_at=null, finalized_at=:finalizedAt, updated_at=:finalizedAt "
                        + "where analysis_run_id=:runId")
                .param("finalizedAt", Timestamp.from(finalizedAt))
                .param("runId", runId)
                .update());

    isolated
        .sql(
            "update "
                + table("analysis_run_dispatches")
                + " set state='FINALIZED', validated_local_proposal=null, retrieval_context=null, "
                + "lease_expires_at=null, finalized_at=:finalizedAt, updated_at=:finalizedAt "
                + "where analysis_run_id=:runId")
        .param("finalizedAt", Timestamp.from(finalizedAt))
        .param("runId", runId)
        .update();

    assertRejected(() -> updateText(isolated, runId, "retrieval_context", CURRENT_CONTEXT));
    assertRejected(() -> updateText(isolated, runId, "retrieval_context_hash", null));

    UUID noneRun = insertRun(isolated, "QUEUED", "PENDING", null, "8");
    insertDispatch(isolated, noneRun, "PREPARED", false, true, LegacyContext.none());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("analysis_run_dispatches")
                        + " set retrieval_context=:context, retrieval_context_hash=:hash "
                        + "where analysis_run_id=:runId")
                .param("context", CURRENT_CONTEXT)
                .param("hash", Hashing.sha256(CURRENT_CONTEXT))
                .param("runId", noneRun)
                .update());
  }

  private JdbcClient createSchemaAndMigrateThroughVersion15() throws SQLException {
    createIsolatedSchema();
    migrate(MigrationVersion.fromVersion("15"));
    return JdbcClient.create(dataSource);
  }

  private JdbcClient createSchemaAndMigrateLatest() throws SQLException {
    createIsolatedSchema();
    migrate(null);
    return JdbcClient.create(dataSource);
  }

  private void createIsolatedSchema() throws SQLException {
    isolatedSchema = "v16_context_" + UUID.randomUUID().toString().replace("-", "");
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
                + "values(:memo,:owner,1,'v16 fixture',:hash,:now,:owner,:now,'Asia/Seoul')")
        .param("memo", MEMO_ID)
        .param("owner", OWNER_ID)
        .param("hash", Hashing.sha256("v16 fixture"))
        .param("now", now)
        .update();
  }

  private UUID insertRun(
      JdbcClient isolated,
      String status,
      String outcome,
      Instant completedAt,
      String tokenCharacter) {
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
                + "values(:id,:owner,:memo,1,'HYBRID',:status,'2','v16-analyzer','none','none','none',"
                + "'v16-policy','NO_NETWORK','v16-gateway','fake','none','no-network-v1',:outcome,"
                + "'durable-v1',:token,'[]',:createdAt,:completedAt)")
        .param("id", runId)
        .param("owner", OWNER_ID)
        .param("memo", MEMO_ID)
        .param("status", status)
        .param("outcome", outcome)
        .param("token", "pmr1_" + tokenCharacter.repeat(64))
        .param("createdAt", Timestamp.from(PREPARED_AT))
        .param("completedAt", completedAt == null ? null : Timestamp.from(completedAt))
        .update();
    return runId;
  }

  private void insertDispatch(
      JdbcClient isolated,
      UUID runId,
      String state,
      boolean scrubbedLocalProposal,
      boolean v16ColumnsExist,
      LegacyContext context) {
    boolean attempted = !"PREPARED".equals(state);
    Instant attemptAt = attempted ? PREPARED_AT.plusSeconds(1) : null;
    Instant leaseAt = "RUNNING".equals(state) ? PREPARED_AT.plusSeconds(2) : null;
    Instant finalizedAt = "FINALIZED".equals(state) ? PREPARED_AT.plusSeconds(3) : null;
    Instant updatedAt =
        finalizedAt != null ? finalizedAt : attemptAt != null ? attemptAt : PREPARED_AT;
    String retrievalColumns =
        v16ColumnsExist
            ? ",retrieval_context,retrieval_context_hash,retrieval_context_version,"
                + "retrieval_context_candidate_count"
            : "";
    String retrievalValues =
        v16ColumnsExist ? ",:context,:contextHash,:contextVersion,:candidateCount" : "";
    var statement =
        isolated
            .sql(
                "insert into "
                    + table("analysis_run_dispatches")
                    + "(analysis_run_id,owner_id,reserved_proposal_id,idempotency_key_hash,"
                    + "request_hash,validated_local_proposal,validated_local_proposal_hash,"
                    + "executor_binding_id,call_timeout_ms,max_attempts,deadline_at,state,fence_token,"
                    + "last_attempt_started_at,lease_expires_at,prepared_at,finalized_at,updated_at"
                    + retrievalColumns
                    + ") values(:runId,:owner,:proposalId,:keyHash,:requestHash,:proposal,:proposalHash,"
                    + ":bindingId,1000,3,:deadline,:state,:fence,:attemptAt,:leaseAt,:preparedAt,"
                    + ":finalizedAt,:updatedAt"
                    + retrievalValues
                    + ")")
            .param("runId", runId)
            .param("owner", OWNER_ID)
            .param("proposalId", UUID.randomUUID())
            .param("keyHash", Hashing.sha256("key-" + runId))
            .param("requestHash", Hashing.sha256("request-" + runId))
            .param("proposal", scrubbedLocalProposal ? null : LOCAL_PROPOSAL)
            .param("proposalHash", Hashing.sha256(LOCAL_PROPOSAL))
            .param("bindingId", "cgb1_" + Hashing.sha256("binding-" + runId))
            .param("deadline", Timestamp.from(PREPARED_AT.plusSeconds(10)))
            .param("state", state)
            .param("fence", attempted ? 1 : 0)
            .param("attemptAt", attemptAt == null ? null : Timestamp.from(attemptAt))
            .param("leaseAt", leaseAt == null ? null : Timestamp.from(leaseAt))
            .param("preparedAt", Timestamp.from(PREPARED_AT))
            .param("finalizedAt", finalizedAt == null ? null : Timestamp.from(finalizedAt))
            .param("updatedAt", Timestamp.from(updatedAt));
    if (v16ColumnsExist) {
      statement
          .param("context", "FINALIZED".equals(state) ? null : context.raw())
          .param("contextHash", context.hash())
          .param("contextVersion", context.version())
          .param("candidateCount", context.candidateCount());
    }
    statement.update();
  }

  private List<ContextEvidence> readContextEvidence(JdbcClient isolated) {
    return isolated
        .sql(
            "select analysis_run_id,state,retrieval_context_version,"
                + "retrieval_context_candidate_count,retrieval_context,retrieval_context_hash "
                + "from "
                + table("analysis_run_dispatches"))
        .query(
            (resultSet, rowNumber) ->
                new ContextEvidence(
                    resultSet.getObject("analysis_run_id", UUID.class),
                    resultSet.getString("state"),
                    resultSet.getString("retrieval_context_version"),
                    resultSet.getInt("retrieval_context_candidate_count"),
                    resultSet.getString("retrieval_context"),
                    resultSet.getString("retrieval_context_hash")))
        .list();
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

  private void updateInteger(JdbcClient isolated, UUID runId, String column, int value) {
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

  private void assertRejected(Runnable mutation) {
    assertThatThrownBy(mutation::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private String table(String tableName) {
    return isolatedSchema + "." + tableName;
  }

  private record LegacyContext(String version, int candidateCount, String raw, String hash) {
    private static LegacyContext none() {
      return new LegacyContext("none", 0, null, null);
    }

    private static LegacyContext current() {
      return new LegacyContext(
          TagRetrievalContext.CURRENT_VERSION, 1, CURRENT_CONTEXT, Hashing.sha256(CURRENT_CONTEXT));
    }
  }

  private record ContextEvidence(
      UUID runId, String state, String version, int candidateCount, String raw, String hash) {}
}
