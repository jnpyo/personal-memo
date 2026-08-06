package local.personalmemo.auth.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.auth.domain.LoginMethod;
import local.personalmemo.auth.domain.UserAccount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
  private final JdbcClient db;

  public AuthRepository(JdbcClient db) {
    this.db = db;
  }

  public Optional<UserAccount> findUser(UUID userId) {
    return db.sql(
            "select id,primary_email,primary_email_normalized,display_name,status "
                + "from users where id=:userId")
        .param("userId", userId)
        .query(this::mapUser)
        .optional();
  }

  public boolean isActive(UUID userId) {
    return db.sql("select exists(select 1 from users where id=:userId and status='ACTIVE')")
        .param("userId", userId)
        .query(Boolean.class)
        .single();
  }

  public void lockAuthenticationScopes(String... scopes) {
    Arrays.stream(scopes)
        .sorted()
        .forEach(
            scope ->
                db.sql("select pg_advisory_xact_lock(hashtextextended(:scope, 0))")
                    .param("scope", "AUTH:" + scope)
                    .query(
                        (row, number) -> {
                          row.getObject(1);
                          return number;
                        })
                    .single());
  }

  public Optional<UserAccount> findUserByNormalizedEmail(String normalizedEmail) {
    return db.sql(
            "select id,primary_email,primary_email_normalized,display_name,status "
                + "from users where primary_email_normalized=:email")
        .param("email", normalizedEmail)
        .query(this::mapUser)
        .optional();
  }

  public Optional<LocalCredential> findLocalCredential(String normalizedEmail) {
    return db.sql(
            "select u.id,u.primary_email,u.primary_email_normalized,u.display_name,u.status,"
                + "c.password_hash,c.locked_until "
                + "from local_credentials c join users u on u.id=c.user_id "
                + "where u.primary_email_normalized=:email")
        .param("email", normalizedEmail)
        .query(
            (row, number) ->
                new LocalCredential(
                    mapUser(row, number),
                    row.getString("password_hash"),
                    row.getTimestamp("locked_until") == null
                        ? null
                        : row.getTimestamp("locked_until").toInstant()))
        .optional();
  }

  public void recordLocalLoginFailure(
      String normalizedEmail, Instant now, int failureLimit, Duration lockDuration) {
    Instant lockUntil = now.plus(lockDuration);
    db.sql(
            """
            update local_credentials c
               set failed_attempts = case
                     when c.locked_until is not null and c.locked_until <= :now then 1
                     else least(c.failed_attempts + 1, 1000000)
                   end,
                   locked_until = case
                     when (case
                       when c.locked_until is not null and c.locked_until <= :now then 1
                       else least(c.failed_attempts + 1, 1000000)
                     end) >= :failureLimit then cast(:lockUntil as timestamptz)
                     else null
                   end
              from users u
             where c.user_id = u.id
               and u.primary_email_normalized = :email
            """)
        .param("now", Timestamp.from(now))
        .param("lockUntil", Timestamp.from(lockUntil))
        .param("failureLimit", failureLimit)
        .param("email", normalizedEmail)
        .update();
  }

  public void clearLocalLoginFailures(UUID userId) {
    db.sql(
            "update local_credentials set failed_attempts=0,locked_until=null "
                + "where user_id=:userId and (failed_attempts<>0 or locked_until is not null)")
        .param("userId", userId)
        .update();
  }

  public UserAccount createLocalUser(
      UUID userId,
      String email,
      String normalizedEmail,
      String displayName,
      String passwordHash,
      String timeZone,
      Instant now) {
    insertUser(userId, email, normalizedEmail, displayName, now);
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:userId,:timeZone,false)")
        .param("userId", userId)
        .param("timeZone", timeZone)
        .update();
    db.sql(
            "insert into local_credentials(user_id,password_hash,password_changed_at) "
                + "values(:userId,:passwordHash,:now)")
        .param("userId", userId)
        .param("passwordHash", passwordHash)
        .param("now", Timestamp.from(now))
        .update();
    return new UserAccount(userId, email, normalizedEmail, displayName, "ACTIVE");
  }

  public UserAccount createGoogleUser(
      UUID userId,
      UUID identityId,
      String email,
      String normalizedEmail,
      String displayName,
      String providerSubject,
      Instant now) {
    insertUser(userId, email, normalizedEmail, displayName, now);
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:userId,'Asia/Seoul',false)")
        .param("userId", userId)
        .update();
    insertGoogleIdentity(identityId, userId, providerSubject, email, true, now);
    return new UserAccount(userId, email, normalizedEmail, displayName, "ACTIVE");
  }

  public Optional<UserAccount> findUserByGoogleSubject(String subject) {
    return db.sql(
            "select u.id,u.primary_email,u.primary_email_normalized,u.display_name,u.status "
                + "from external_identities e join users u on u.id=e.user_id "
                + "where e.provider='GOOGLE' and e.provider_subject=:subject")
        .param("subject", subject)
        .query(this::mapUser)
        .optional();
  }

  public Optional<String> findGoogleSubject(UUID userId) {
    return db.sql(
            "select provider_subject from external_identities "
                + "where user_id=:userId and provider='GOOGLE'")
        .param("userId", userId)
        .query(String.class)
        .optional();
  }

  public void linkGoogle(
      UUID identityId,
      UUID userId,
      String providerSubject,
      String email,
      boolean emailVerified,
      Instant now) {
    insertGoogleIdentity(identityId, userId, providerSubject, email, emailVerified, now);
  }

  public boolean touchGoogleLogin(String subject, String email, Instant now) {
    return db.sql(
                "update external_identities set email_at_login=:email,email_verified=true,last_login_at=:now "
                    + "where provider='GOOGLE' and provider_subject=:subject")
            .param("email", email)
            .param("now", Timestamp.from(now))
            .param("subject", subject)
            .update()
        == 1;
  }

  public boolean hasLocalCredential(UUID userId) {
    return db.sql("select exists(select 1 from local_credentials where user_id=:userId)")
        .param("userId", userId)
        .query(Boolean.class)
        .single();
  }

  public boolean unlinkGoogle(UUID userId, String providerSubject) {
    return db.sql(
                "delete from external_identities "
                    + "where user_id=:userId and provider='GOOGLE' and provider_subject=:subject")
            .param("userId", userId)
            .param("subject", providerSubject)
            .update()
        == 1;
  }

  public Set<LoginMethod> loginMethods(UUID userId) {
    LinkedHashSet<LoginMethod> methods = new LinkedHashSet<>();
    if (hasLocalCredential(userId)) {
      methods.add(LoginMethod.LOCAL);
    }
    boolean google =
        db.sql(
                "select exists(select 1 from external_identities "
                    + "where user_id=:userId and provider='GOOGLE')")
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    if (google) {
      methods.add(LoginMethod.GOOGLE);
    }
    return Set.copyOf(methods);
  }

  private void insertUser(
      UUID userId, String email, String normalizedEmail, String displayName, Instant now) {
    db.sql(
            "insert into users(id,primary_email,primary_email_normalized,display_name,status,created_at,updated_at) "
                + "values(:id,:email,:normalizedEmail,:displayName,'ACTIVE',:now,:now)")
        .param("id", userId)
        .param("email", email)
        .param("normalizedEmail", normalizedEmail)
        .param("displayName", displayName)
        .param("now", Timestamp.from(now))
        .update();
  }

  private void insertGoogleIdentity(
      UUID identityId,
      UUID userId,
      String subject,
      String email,
      boolean emailVerified,
      Instant now) {
    db.sql(
            "insert into external_identities(id,user_id,provider,provider_subject,email_at_login,email_verified,linked_at,last_login_at) "
                + "values(:id,:userId,'GOOGLE',:subject,:email,:verified,:now,:now)")
        .param("id", identityId)
        .param("userId", userId)
        .param("subject", subject)
        .param("email", email)
        .param("verified", emailVerified)
        .param("now", Timestamp.from(now))
        .update();
  }

  private UserAccount mapUser(ResultSet row, int number) throws SQLException {
    return new UserAccount(
        row.getObject("id", UUID.class),
        row.getString("primary_email"),
        row.getString("primary_email_normalized"),
        row.getString("display_name"),
        row.getString("status"));
  }

  public record LocalCredential(UserAccount user, String passwordHash, Instant lockedUntil) {}
}
