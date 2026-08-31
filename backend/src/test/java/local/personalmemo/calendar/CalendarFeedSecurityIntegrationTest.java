package local.personalmemo.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.calendar.domain.CalendarFeedSecret;
import local.personalmemo.support.PostgresIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@PostgresIntegration
@SpringBootTest(
    properties = {
      "app.calendar-feed.publication.enabled=false",
      "app.calendar-feed.publication.public-origin=",
      "app.calendar-feed.publication.consent-policy-version="
    })
@AutoConfigureMockMvc(addFilters = true)
class CalendarFeedSecurityIntegrationTest {
  private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static PostgreSQLContainer postgres;

  @DynamicPropertySource
  static synchronized void databaseProperties(DynamicPropertyRegistry registry) {
    String externalUrl = System.getenv("TEST_DATABASE_URL");
    if (externalUrl != null && !externalUrl.isBlank()) {
      registry.add("spring.datasource.url", () -> externalUrl);
      registry.add(
          "spring.datasource.username",
          () -> environmentOrDefault("TEST_DATABASE_USERNAME", "personal_memo"));
      registry.add(
          "spring.datasource.password",
          () -> environmentOrDefault("TEST_DATABASE_PASSWORD", "test-only"));
      return;
    }
    if (postgres == null) {
      postgres =
          new PostgreSQLContainer("postgres:17.6-alpine")
              .withDatabaseName("personal_memo_calendar_security_test")
              .withUsername("personal_memo")
              .withPassword("test-only");
      postgres.start();
    }
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient db;

  private String token;

  @BeforeEach
  void seedActiveOwnerAndEmptyFeed() {
    db.sql(
            "truncate table spring_session_attributes, spring_session, "
                + "idempotency_records, users cascade")
        .update();
    Timestamp now = Timestamp.from(Instant.parse("2026-08-25T05:00:00Z"));
    db.sql(
            """
            insert into users(
              id, created_at, updated_at, primary_email, primary_email_normalized,
              display_name, status
            ) values (
              :ownerId, :now, :now, 'calendar-security@example.test',
              'calendar-security@example.test', 'Calendar Security', 'ACTIVE'
            )
            """)
        .param("ownerId", OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:ownerId,'Asia/Seoul',false)")
        .param("ownerId", OWNER_ID)
        .update();
    token = secret(31);
    db.sql(
            """
            insert into calendar_feeds(
              id, owner_id, display_name, disclosure_mode, status, version, token_verifier,
              created_at, updated_at, rotated_at, revoked_at
            ) values (
              :id, :ownerId, 'empty security feed', 'BUSY_ONLY', 'ACTIVE', 1, :verifier,
              :now, :now, :now, null
            )
            """)
        .param("id", UUID.randomUUID())
        .param("ownerId", OWNER_ID)
        .param("verifier", CalendarFeedSecret.requireVerifier(token))
        .param("now", now)
        .update();
  }

  @Test
  void publicChainIsStatelessAndAllowsOnlyGetOrHeadOnTheExactFixedPath() throws Exception {
    MvcResult valid =
        mvc.perform(
                get("/calendar/v1/feed.ics")
                    .queryParam("token", token)
                    .cookie(new Cookie("SESSION", "ignored-session")))
            .andReturn();
    assertThat(valid.getResponse().getStatus()).isEqualTo(204);
    assertThat(valid.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(valid.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(valid.getResponse().getHeader("Set-Cookie")).isNull();
    assertThat(valid.getRequest().getSession(false)).isNull();

    MvcResult validHead =
        mvc.perform(head("/calendar/v1/feed.ics").queryParam("token", token)).andReturn();
    assertThat(validHead.getResponse().getStatus()).isEqualTo(204);
    assertThat(validHead.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(validHead.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(validHead.getResponse().getContentAsByteArray()).isEmpty();
    assertThat(validHead.getRequest().getSession(false)).isNull();

    MvcResult write =
        mvc.perform(post("/calendar/v1/feed.ics").queryParam("token", token)).andReturn();
    assertGenericNotFound(write);

    MvcResult management = mvc.perform(get("/api/v1/calendar-feeds")).andReturn();
    assertThat(management.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(management).path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");

    MvcResult nonExact =
        mvc.perform(get("/calendar/v1/feed.ics/extra").queryParam("token", token)).andReturn();
    assertThat(nonExact.getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void publicationCapabilitiesAreAuthenticatedNoStoreAndDefaultToLocalOnly() throws Exception {
    MvcResult unauthenticated = mvc.perform(get("/api/v1/calendar-feeds/capabilities")).andReturn();
    assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(unauthenticated).path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");

    MvcResult registration = register("calendar-capabilities@example.test");
    assertThat(registration.getResponse().getStatus()).isEqualTo(201);
    Cookie session = requireCookie(registration, "SESSION");
    UUID ownerId = UUID.fromString(body(registration).path("userId").asText());

    MvcResult authenticated =
        mvc.perform(
                get("/api/v1/calendar-feeds/capabilities")
                    .cookie(session)
                    .header("X-Expected-Owner-Id", ownerId))
            .andReturn();
    assertThat(authenticated.getResponse().getStatus()).isEqualTo(200);
    assertThat(authenticated.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(body(authenticated).path("mode").asText()).isEqualTo("LOCAL_ONLY");
    assertThat(body(authenticated).path("publicOrigin").isNull()).isTrue();
  }

  @Test
  void malformedUnknownDuplicateAndExtraQueriesShareTheSameEmpty404Shape() throws Exception {
    assertGenericNotFound(
        mvc.perform(get("/calendar/v1/feed.ics").queryParam("token", "bad")).andReturn());
    assertGenericNotFound(
        mvc.perform(get("/calendar/v1/feed.ics").queryParam("token", secret(32))).andReturn());
    assertGenericNotFound(
        mvc.perform(get("/calendar/v1/feed.ics").queryParam("token", token, token)).andReturn());
    assertGenericNotFound(
        mvc.perform(
                get("/calendar/v1/feed.ics")
                    .queryParam("token", token)
                    .queryParam("unexpected", "value"))
            .andReturn());
  }

  @Test
  void managementCreateRequiresAuthenticatedSessionValidCsrfAndMatchingExpectedOwner()
      throws Exception {
    MvcResult registration = register("calendar-manager@example.test");
    assertThat(registration.getResponse().getStatus()).isEqualTo(201);
    Cookie session = requireCookie(registration, "SESSION");
    UUID ownerId = UUID.fromString(body(registration).path("userId").asText());
    CalendarFeedTestData.Seed event =
        CalendarFeedTestData.timed(
            db, ownerId, "인증된 공유 일정", Instant.parse("2026-09-06T09:00:00Z"), null);
    byte[] requestBody =
        json.writeValueAsBytes(
            Map.of(
                "displayName",
                "인증 수신자",
                "disclosureMode",
                "TITLE",
                "eventIds",
                List.of(event.eventId()),
                "bearerSecret",
                secret(40)));

    MvcResult missingCsrf =
        mvc.perform(
                post("/api/v1/calendar-feeds")
                    .cookie(session)
                    .header("X-Expected-Owner-Id", ownerId)
                    .header("Idempotency-Key", "security-create-missing-csrf")
                    .contentType("application/json")
                    .content(requestBody))
            .andReturn();
    assertThat(missingCsrf.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(missingCsrf).path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");

    ActualCsrf csrf = actualCsrf(session);
    MvcResult wrongCsrf =
        mvc.perform(
                post("/api/v1/calendar-feeds")
                    .cookie(session, csrf.cookie())
                    .header(csrf.headerName(), "wrong-token")
                    .header("X-Expected-Owner-Id", ownerId)
                    .header("Idempotency-Key", "security-create-wrong-csrf")
                    .contentType("application/json")
                    .content(requestBody))
            .andReturn();
    assertThat(wrongCsrf.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(wrongCsrf).path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");

    MvcResult staleOwner =
        mvc.perform(
                post("/api/v1/calendar-feeds")
                    .cookie(session, csrf.cookie())
                    .header(csrf.headerName(), csrf.token())
                    .header("X-Expected-Owner-Id", UUID.randomUUID())
                    .header("Idempotency-Key", "security-create-stale-owner")
                    .contentType("application/json")
                    .content(requestBody))
            .andReturn();
    assertThat(staleOwner.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(staleOwner).path("code").asText()).isEqualTo("SESSION_OWNER_CHANGED");
    assertThat(
            db.sql("select count(*) from calendar_feeds where owner_id = :ownerId")
                .param("ownerId", ownerId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql("select count(*) from idempotency_records where owner_id = :ownerId")
                .param("ownerId", ownerId)
                .query(Long.class)
                .single())
        .isZero();

    MvcResult created =
        mvc.perform(
                post("/api/v1/calendar-feeds")
                    .cookie(session, csrf.cookie())
                    .header(csrf.headerName(), csrf.token())
                    .header("X-Expected-Owner-Id", ownerId)
                    .header("Idempotency-Key", "security-create-valid")
                    .contentType("application/json")
                    .content(requestBody))
            .andReturn();
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    UUID feedId = UUID.fromString(body(created).path("id").asText());
    assertThat(
            db.sql("select owner_id from calendar_feeds where id = :feedId")
                .param("feedId", feedId)
                .query(UUID.class)
                .single())
        .isEqualTo(ownerId);
  }

  @Test
  void externalPublicationEnableRequiresAuthenticationCsrfAndMatchingExpectedOwner()
      throws Exception {
    UUID feedId = UUID.randomUUID();
    byte[] requestBody =
        json.writeValueAsBytes(
            Map.of(
                "expectedVersion",
                1,
                "bearerSecret",
                secret(51),
                "consentPolicyVersion",
                "calendar-feed-public-v1"));

    ActualCsrf anonymousCsrf = actualCsrf(null);
    MvcResult unauthenticated =
        mvc.perform(
                post("/api/v1/calendar-feeds/{id}/external-publication/enable", feedId)
                    .cookie(anonymousCsrf.cookie())
                    .header(anonymousCsrf.headerName(), anonymousCsrf.token())
                    .header("Idempotency-Key", "security-enable-unauthenticated")
                    .contentType("application/json")
                    .content(requestBody))
            .andReturn();
    assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(unauthenticated).path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");

    MvcResult registration = register("calendar-external-publication@example.test");
    assertThat(registration.getResponse().getStatus()).isEqualTo(201);
    Cookie session = requireCookie(registration, "SESSION");
    UUID ownerId = UUID.fromString(body(registration).path("userId").asText());
    Timestamp now = Timestamp.from(Instant.parse("2026-08-25T06:00:00Z"));
    db.sql(
            """
            insert into calendar_feeds(
              id, owner_id, display_name, disclosure_mode, status, version, token_verifier,
              created_at, updated_at, rotated_at, revoked_at
            ) values (
              :id, :ownerId, 'external publication security', 'BUSY_ONLY', 'ACTIVE', 1,
              :verifier, :now, :now, :now, null
            )
            """)
        .param("id", feedId)
        .param("ownerId", ownerId)
        .param("verifier", CalendarFeedSecret.requireVerifier(secret(50)))
        .param("now", now)
        .update();

    MvcResult missingCsrf =
        mvc.perform(
                post("/api/v1/calendar-feeds/{id}/external-publication/enable", feedId)
                    .cookie(session)
                    .header("X-Expected-Owner-Id", ownerId)
                    .header("Idempotency-Key", "security-enable-missing-csrf")
                    .contentType("application/json")
                    .content(requestBody))
            .andReturn();
    assertThat(missingCsrf.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(missingCsrf).path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");

    ActualCsrf csrf = actualCsrf(session);
    MvcResult staleOwner =
        mvc.perform(
                post("/api/v1/calendar-feeds/{id}/external-publication/enable", feedId)
                    .cookie(session, csrf.cookie())
                    .header(csrf.headerName(), csrf.token())
                    .header("X-Expected-Owner-Id", UUID.randomUUID())
                    .header("Idempotency-Key", "security-enable-stale-owner")
                    .contentType("application/json")
                    .content(requestBody))
            .andReturn();
    assertThat(staleOwner.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(staleOwner).path("code").asText()).isEqualTo("SESSION_OWNER_CHANGED");

    MvcResult unavailable =
        mvc.perform(
                post("/api/v1/calendar-feeds/{id}/external-publication/enable", feedId)
                    .cookie(session, csrf.cookie())
                    .header(csrf.headerName(), csrf.token())
                    .header("X-Expected-Owner-Id", ownerId)
                    .header("Idempotency-Key", "security-enable-disabled")
                    .contentType("application/json")
                    .content(requestBody))
            .andReturn();
    assertThat(unavailable.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(unavailable).path("code").asText())
        .isEqualTo("CALENDAR_FEED_PUBLICATION_UNAVAILABLE");
    assertThat(
            db.sql("select version from calendar_feeds where id = :feedId")
                .param("feedId", feedId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  private void assertGenericNotFound(MvcResult result) {
    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(result.getResponse().getContentType()).isNull();
    assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
  }

  private String secret(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private MvcResult register(String email) throws Exception {
    ActualCsrf csrf = actualCsrf(null);
    return mvc.perform(
            post("/api/v1/auth/register")
                .cookie(csrf.cookie())
                .header(csrf.headerName(), csrf.token())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "email",
                            email,
                            "password",
                            "correct horse battery",
                            "displayName",
                            "Calendar Manager",
                            "timeZone",
                            "Asia/Seoul"))))
        .andReturn();
  }

  private ActualCsrf actualCsrf(Cookie session) throws Exception {
    MockHttpServletRequestBuilder request = get("/api/v1/auth/csrf");
    if (session != null) {
      request.cookie(session);
    }
    MvcResult result = mvc.perform(request).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    Cookie cookie = requireCookie(result, "XSRF-TOKEN");
    JsonNode response = body(result);
    return new ActualCsrf(
        cookie, response.path("headerName").asText(), response.path("token").asText());
  }

  private Cookie requireCookie(MvcResult result, String name) {
    Cookie cookie = result.getResponse().getCookie(name);
    assertThat(cookie).as(name + " cookie").isNotNull();
    return cookie;
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray());
  }

  private static String environmentOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private record ActualCsrf(Cookie cookie, String headerName, String token) {}
}
