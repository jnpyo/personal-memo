package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
class CloudExecutionSnapshotMigrationIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID MEMO_ID = UUID.fromString("30000000-0000-0000-0000-000000000014");
  private static final UUID LOCAL_RUN = UUID.fromString("41000000-0000-0000-0000-000000000014");
  private static final UUID NO_NETWORK_RUN =
      UUID.fromString("42000000-0000-0000-0000-000000000014");
  private static final UUID EXTERNAL_DENIED_RUN =
      UUID.fromString("43000000-0000-0000-0000-000000000014");
  private static final UUID EXTERNAL_CALLED_RUN =
      UUID.fromString("44000000-0000-0000-0000-000000000014");
  private static final UUID LEGACY_UNKNOWN_RUN =
      UUID.fromString("45000000-0000-0000-0000-000000000014");
  private static final String FIRST_TOKEN = "pmr1_" + "1".repeat(64);
  private static final String SECOND_TOKEN = "pmr1_" + "2".repeat(64);
  private static final String THIRD_TOKEN = "pmr1_" + "3".repeat(64);

  @Autowired private DataSource dataSource;

  private String isolatedSchema;

  @AfterEach
  void removeIsolatedSchema() throws SQLException {
    if (isolatedSchema == null || !isolatedSchema.startsWith("v14_contract_")) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema " + isolatedSchema + " cascade");
    }
  }

  @Test
  void v14PreservesLegacyTruthAndEnforcesNewExecutionSnapshots() throws Exception {
    isolatedSchema = "v14_contract_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + isolatedSchema);
    }

    migrateThroughVersion13();
    seedVersion13Runs();
    migrateToLatest();

    JdbcClient isolated = JdbcClient.create(dataSource);
    assertThat(readSnapshots(isolated))
        .containsExactlyInAnyOrder(
            Snapshot.legacy(LOCAL_RUN),
            Snapshot.legacy(NO_NETWORK_RUN),
            Snapshot.legacy(EXTERNAL_DENIED_RUN),
            Snapshot.legacy(EXTERNAL_CALLED_RUN),
            Snapshot.legacy(LEGACY_UNKNOWN_RUN));
    assertThat(
            isolated
                .sql(
                    "select column_default from information_schema.columns where table_schema=:schema and table_name='analysis_runs' and column_name='cloud_execution_contract_version'")
                .param("schema", isolatedSchema)
                .query(String.class)
                .optional())
        .isEmpty();

    updateSnapshot(isolated, LOCAL_RUN, "snapshot-v1", null, null, null);
    updateSnapshot(isolated, NO_NETWORK_RUN, "snapshot-v1", null, null, FIRST_TOKEN);
    updateSnapshot(
        isolated, EXTERNAL_DENIED_RUN, "snapshot-v1", "2026-08-10T01:00:00Z", null, null);
    updateSnapshot(
        isolated,
        EXTERNAL_CALLED_RUN,
        "snapshot-v1",
        "2026-08-10T01:00:00Z",
        "2026-08-10T00:00:00Z",
        SECOND_TOKEN);

    assertRejected(() -> updateSnapshot(isolated, NO_NETWORK_RUN, "snapshot-v1", null, null, null));
    assertRejected(
        () ->
            updateSnapshot(
                isolated,
                EXTERNAL_DENIED_RUN,
                "snapshot-v1",
                "2026-08-10T01:00:00Z",
                null,
                THIRD_TOKEN));
    assertRejected(
        () -> updateSnapshot(isolated, EXTERNAL_DENIED_RUN, "snapshot-v1", null, null, null));
    assertRejected(
        () ->
            updateSnapshot(
                isolated,
                EXTERNAL_CALLED_RUN,
                "snapshot-v1",
                "2026-08-10T01:00:00Z",
                "2026-08-10T02:00:00Z",
                SECOND_TOKEN));
    assertRejected(
        () ->
            updateSnapshot(
                isolated,
                EXTERNAL_CALLED_RUN,
                "snapshot-v1",
                "2026-08-10T01:00:00Z",
                null,
                SECOND_TOKEN));
    assertRejected(
        () ->
            updateSnapshot(isolated, LOCAL_RUN, "legacy-v0", null, null, "pmr1_" + "4".repeat(64)));
    assertRejected(() -> updateSnapshot(isolated, LOCAL_RUN, "unknown-v1", null, null, null));
    assertRejected(
        () -> updateSnapshot(isolated, NO_NETWORK_RUN, "snapshot-v1", null, null, SECOND_TOKEN));
    assertRejected(
        () -> updateSnapshot(isolated, NO_NETWORK_RUN, "snapshot-v1", null, null, "not-a-token"));
  }

  private void migrateThroughVersion13() {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(isolatedSchema)
        .defaultSchema(isolatedSchema)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("13"))
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

  private void seedVersion13Runs() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into "
              + table("memos")
              + "(id,owner_id,current_revision,status,pinned,created_at,updated_at) values ('"
              + MEMO_ID
              + "','"
              + OWNER_ID
              + "',1,'ACTIVE',false,now(),now())");
      statement.executeUpdate(
          "insert into "
              + table("memo_revisions")
              + "(memo_id,revision,content,content_hash,created_at,created_by,owner_id,client_recorded_at,source_time_zone) values ('"
              + MEMO_ID
              + "',1,'snapshot fixture',repeat('a',64),now(),'"
              + OWNER_ID
              + "','"
              + OWNER_ID
              + "',now(),'Asia/Seoul')");
      insertRun(
          statement,
          LOCAL_RUN,
          "LOCAL",
          "NOT_REQUIRED",
          "none",
          "none",
          "none",
          "none",
          "NOT_REQUIRED");
      insertRun(
          statement,
          NO_NETWORK_RUN,
          "HYBRID",
          "NO_NETWORK",
          "fake-cloud-v2",
          "fake",
          "none",
          "no-network-v1",
          "SUCCESS");
      insertRun(
          statement,
          EXTERNAL_DENIED_RUN,
          "HYBRID",
          "EXTERNAL_MEMO_CONTENT",
          "gateway-v1",
          "provider-v1",
          "model-v1",
          "policy-v1",
          "CONSENT_REQUIRED");
      insertRun(
          statement,
          EXTERNAL_CALLED_RUN,
          "HYBRID",
          "EXTERNAL_MEMO_CONTENT",
          "gateway-v1",
          "provider-v1",
          "model-v1",
          "policy-v1",
          "SUCCESS");
      insertRun(
          statement,
          LEGACY_UNKNOWN_RUN,
          "HYBRID",
          "LEGACY_UNKNOWN",
          "legacy-unknown",
          "legacy-unknown",
          "legacy-unknown",
          "legacy-unknown",
          "LEGACY_UNKNOWN");
    }
  }

  private void insertRun(
      Statement statement,
      UUID runId,
      String route,
      String transferMode,
      String gatewayVersion,
      String providerId,
      String modelVersion,
      String consentPolicyVersion,
      String outcome)
      throws SQLException {
    statement.executeUpdate(
        "insert into "
            + table("analysis_runs")
            + "(id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,prompt_version,local_model_version,embedding_model_version,routing_policy_version,ambiguity_reasons,created_at,completed_at,cloud_transfer_mode,cloud_gateway_version,cloud_provider_id,cloud_model_version,cloud_consent_policy_version,cloud_outcome) values ('"
            + runId
            + "','"
            + OWNER_ID
            + "','"
            + MEMO_ID
            + "',1,'"
            + route
            + "','REVIEW_REQUIRED','2','legacy-v13','none','none','none','policy-v1','[]',now(),now(),'"
            + transferMode
            + "','"
            + gatewayVersion
            + "','"
            + providerId
            + "','"
            + modelVersion
            + "','"
            + consentPolicyVersion
            + "','"
            + outcome
            + "')");
  }

  private List<Snapshot> readSnapshots(JdbcClient isolated) {
    return isolated
        .sql(
            "select id,cloud_execution_contract_version,cloud_authorization_checked_at,cloud_accepted_consent_granted_at,cloud_provider_request_token from "
                + table("analysis_runs"))
        .query(
            (ResultSet resultSet, int rowNumber) ->
                new Snapshot(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("cloud_execution_contract_version"),
                    resultSet.getTimestamp("cloud_authorization_checked_at") != null,
                    resultSet.getTimestamp("cloud_accepted_consent_granted_at") != null,
                    resultSet.getString("cloud_provider_request_token")))
        .list();
  }

  private void updateSnapshot(
      JdbcClient isolated,
      UUID runId,
      String version,
      String authorizationCheckedAt,
      String acceptedConsentGrantedAt,
      String token) {
    isolated
        .sql(
            "update "
                + table("analysis_runs")
                + " set cloud_execution_contract_version=:version, cloud_authorization_checked_at=cast(:checkedAt as timestamptz), cloud_accepted_consent_granted_at=cast(:grantedAt as timestamptz), cloud_provider_request_token=:token where id=:runId")
        .param("version", version)
        .param("checkedAt", authorizationCheckedAt)
        .param("grantedAt", acceptedConsentGrantedAt)
        .param("token", token)
        .param("runId", runId)
        .update();
  }

  private void assertRejected(Runnable mutation) {
    assertThatThrownBy(mutation::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private String table(String tableName) {
    return isolatedSchema + "." + tableName;
  }

  private record Snapshot(
      UUID runId,
      String contractVersion,
      boolean hasAuthorizationCheckedAt,
      boolean hasAcceptedConsentGrantedAt,
      String providerRequestToken) {
    private static Snapshot legacy(UUID runId) {
      return new Snapshot(runId, "legacy-v0", false, false, null);
    }
  }
}
