package local.personalmemo.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
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
class CalendarFeedPublicConsentMigrationIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired private DataSource dataSource;

  private String isolatedSchema;

  @AfterEach
  void removeIsolatedSchema() throws SQLException {
    if (isolatedSchema == null || !isolatedSchema.startsWith("v23_calendar_feed_")) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema " + isolatedSchema + " cascade");
    }
  }

  @Test
  void v23KeepsExistingFeedsLocalAndEnforcesCoherentExplicitConsent() throws SQLException {
    isolatedSchema = "v23_calendar_feed_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + isolatedSchema);
    }
    migrate(MigrationVersion.fromVersion("22"));

    UUID feedId = UUID.randomUUID();
    JdbcClient isolated = JdbcClient.create(dataSource);
    isolated
        .sql(
            """
            insert into %s(
              id, owner_id, display_name, disclosure_mode, status, version, token_verifier,
              created_at, updated_at, rotated_at, revoked_at
            ) values (
              :feedId, :ownerId, 'legacy local feed', 'BUSY_ONLY', 'ACTIVE', 1,
              :verifier, now(), now(), now(), null
            )
            """
                .formatted(table("calendar_feeds")))
        .param("feedId", feedId)
        .param("ownerId", OWNER_ID)
        .param("verifier", "a".repeat(64))
        .update();

    migrate(null);

    ConsentState migrated = readState(isolated, feedId);
    assertThat(migrated).isEqualTo(new ConsentState("LOCAL_ONLY", null, false, "ACTIVE", null));

    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("calendar_feeds")
                        + " set publication_scope='PUBLIC_HTTPS' where id=:feedId")
                .param("feedId", feedId)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("calendar_feeds")
                        + " set publication_scope='PUBLIC_HTTPS', "
                        + "public_consent_policy_version='Calendar-Feed-Public-v1', "
                        + "public_consent_granted_at=updated_at where id=:feedId")
                .param("feedId", feedId)
                .update());

    isolated
        .sql(
            "update "
                + table("calendar_feeds")
                + " set publication_scope='PUBLIC_HTTPS', "
                + "public_consent_policy_version='calendar-feed-public-v1', "
                + "public_consent_granted_at=updated_at where id=:feedId")
        .param("feedId", feedId)
        .update();
    assertThat(readState(isolated, feedId))
        .isEqualTo(
            new ConsentState("PUBLIC_HTTPS", "calendar-feed-public-v1", true, "ACTIVE", null));

    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("calendar_feeds")
                        + " set status='REVOKED', revoked_at=updated_at where id=:feedId")
                .param("feedId", feedId)
                .update());
    assertRejected(
        () ->
            isolated
                .sql(
                    "update "
                        + table("calendar_feeds")
                        + " set publication_scope='LOCAL_ONLY' where id=:feedId")
                .param("feedId", feedId)
                .update());
  }

  private ConsentState readState(JdbcClient isolated, UUID feedId) {
    return isolated
        .sql(
            "select publication_scope, public_consent_policy_version, "
                + "public_consent_granted_at, status, revoked_at from "
                + table("calendar_feeds")
                + " where id=:feedId")
        .param("feedId", feedId)
        .query(
            (resultSet, rowNumber) ->
                new ConsentState(
                    resultSet.getString("publication_scope"),
                    resultSet.getString("public_consent_policy_version"),
                    resultSet.getTimestamp("public_consent_granted_at") != null,
                    resultSet.getString("status"),
                    resultSet.getTimestamp("revoked_at")))
        .single();
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

  private void assertRejected(Runnable mutation) {
    assertThatThrownBy(mutation::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private String table(String tableName) {
    return isolatedSchema + "." + tableName;
  }

  private record ConsentState(
      String publicationScope,
      String policyVersion,
      boolean grantedAtPresent,
      String status,
      java.sql.Timestamp revokedAt) {}
}
