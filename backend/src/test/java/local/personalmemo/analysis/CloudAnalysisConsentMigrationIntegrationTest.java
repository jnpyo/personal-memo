package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
class CloudAnalysisConsentMigrationIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID MEMO_ID = UUID.fromString("30000000-0000-0000-0000-000000000013");
  private static final UUID LOCAL_RUN_ID = UUID.fromString("40000000-0000-0000-0000-000000000013");
  private static final UUID HYBRID_RUN_ID = UUID.fromString("50000000-0000-0000-0000-000000000013");

  @Autowired private DataSource dataSource;

  private String isolatedSchema;

  @AfterEach
  void removeIsolatedSchema() throws SQLException {
    if (isolatedSchema == null || !isolatedSchema.startsWith("v13_contract_")) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema " + isolatedSchema + " cascade");
    }
  }

  @Test
  void v13RevokesLegacyConsentAndBackfillsTruthfulCloudEvidence() throws Exception {
    isolatedSchema = "v13_contract_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + isolatedSchema);
    }

    migrateThroughVersion12();
    seedVersion12State();
    migrateToLatest();

    JdbcClient isolated = JdbcClient.create(dataSource);
    ConsentState consent =
        isolated
            .sql(
                "select cloud_analysis_consent, cloud_analysis_consent_policy_version, "
                    + "cloud_analysis_consent_granted_at, settings_version from "
                    + table("user_settings")
                    + " where user_id=:ownerId")
            .param("ownerId", OWNER_ID)
            .query(
                (resultSet, rowNumber) ->
                    new ConsentState(
                        resultSet.getBoolean("cloud_analysis_consent"),
                        resultSet.getString("cloud_analysis_consent_policy_version"),
                        resultSet.getTimestamp("cloud_analysis_consent_granted_at") != null,
                        resultSet.getLong("settings_version")))
            .single();
    assertThat(consent.granted()).isFalse();
    assertThat(consent.policyVersion()).isNull();
    assertThat(consent.hasGrantedAt()).isFalse();
    assertThat(consent.settingsVersion()).isEqualTo(8L);

    assertThat(readEvidence(isolated, LOCAL_RUN_ID))
        .isEqualTo(new Evidence("NOT_REQUIRED", "none", "none", "none", "none", "NOT_REQUIRED"));
    assertThat(readEvidence(isolated, HYBRID_RUN_ID))
        .isEqualTo(
            new Evidence(
                "LEGACY_UNKNOWN",
                "legacy-unknown",
                "legacy-unknown",
                "legacy-unknown",
                "legacy-unknown",
                "LEGACY_UNKNOWN"));

    assertThatThrownBy(
            () ->
                isolated
                    .sql(
                        "update "
                            + table("analysis_runs")
                            + " set cloud_transfer_mode='NOT_REQUIRED', "
                            + "cloud_gateway_version='none', cloud_provider_id='none', "
                            + "cloud_model_version='none', cloud_consent_policy_version='none', "
                            + "cloud_outcome='NOT_REQUIRED' where id=:runId")
                    .param("runId", HYBRID_RUN_ID)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                isolated
                    .sql(
                        "update "
                            + table("analysis_runs")
                            + " set cloud_transfer_mode='NO_NETWORK', "
                            + "cloud_gateway_version='gateway-v1', cloud_provider_id='provider-v1', "
                            + "cloud_model_version='none', cloud_consent_policy_version='policy-v1', "
                            + "cloud_outcome='CONSENT_REQUIRED' where id=:runId")
                    .param("runId", HYBRID_RUN_ID)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                isolated
                    .sql(
                        "update "
                            + table("user_settings")
                            + " set cloud_analysis_consent=false, "
                            + "cloud_analysis_consent_policy_version='policy-v1', "
                            + "cloud_analysis_consent_granted_at=now() where user_id=:ownerId")
                    .param("ownerId", OWNER_ID)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                isolated
                    .sql(
                        "update "
                            + table("user_settings")
                            + " set cloud_analysis_consent=true, "
                            + "cloud_analysis_consent_policy_version=null, "
                            + "cloud_analysis_consent_granted_at=now() where user_id=:ownerId")
                    .param("ownerId", OWNER_ID)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void migrateThroughVersion12() {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(isolatedSchema)
        .defaultSchema(isolatedSchema)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("12"))
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

  private void seedVersion12State() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "update "
              + table("user_settings")
              + " set cloud_analysis_consent=true, settings_version=7 where user_id='"
              + OWNER_ID
              + "'");
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
              + "(memo_id,revision,content,content_hash,created_at,created_by,owner_id,"
              + "client_recorded_at,source_time_zone) values ('"
              + MEMO_ID
              + "',1,'legacy memo',repeat('a',64),now(),'"
              + OWNER_ID
              + "','"
              + OWNER_ID
              + "',now(),'Asia/Seoul')");
      insertLegacyRun(statement, LOCAL_RUN_ID, "LOCAL");
      insertLegacyRun(statement, HYBRID_RUN_ID, "HYBRID");
    }
  }

  private void insertLegacyRun(Statement statement, UUID runId, String route) throws SQLException {
    statement.executeUpdate(
        "insert into "
            + table("analysis_runs")
            + "(id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,"
            + "prompt_version,local_model_version,embedding_model_version,routing_policy_version,"
            + "ambiguity_reasons,created_at,completed_at) values ('"
            + runId
            + "','"
            + OWNER_ID
            + "','"
            + MEMO_ID
            + "',1,'"
            + route
            + "','REVIEW_REQUIRED','2','legacy-v1','none','none','none','legacy-policy-v1',"
            + "'[]',now(),now())");
  }

  private Evidence readEvidence(JdbcClient isolated, UUID runId) {
    return isolated
        .sql(
            "select cloud_transfer_mode, cloud_gateway_version, cloud_provider_id, "
                + "cloud_model_version, cloud_consent_policy_version, cloud_outcome from "
                + table("analysis_runs")
                + " where id=:runId")
        .param("runId", runId)
        .query(
            (ResultSet resultSet, int rowNumber) ->
                new Evidence(
                    resultSet.getString("cloud_transfer_mode"),
                    resultSet.getString("cloud_gateway_version"),
                    resultSet.getString("cloud_provider_id"),
                    resultSet.getString("cloud_model_version"),
                    resultSet.getString("cloud_consent_policy_version"),
                    resultSet.getString("cloud_outcome")))
        .single();
  }

  private String table(String tableName) {
    return isolatedSchema + "." + tableName;
  }

  private record ConsentState(
      boolean granted, String policyVersion, boolean hasGrantedAt, long settingsVersion) {}

  private record Evidence(
      String transferMode,
      String gatewayVersion,
      String providerId,
      String modelVersion,
      String consentPolicyVersion,
      String outcome) {}
}
