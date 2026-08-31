package local.personalmemo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.personalmemo.auth.application.AuthService;
import local.personalmemo.auth.domain.GoogleProfile;
import local.personalmemo.auth.domain.LoginMethod;
import local.personalmemo.auth.infrastructure.AuthRepository;
import local.personalmemo.auth.infrastructure.GoogleOAuthFailureHandler;
import local.personalmemo.auth.infrastructure.GoogleOAuthSuccessHandler;
import local.personalmemo.auth.infrastructure.LinkAwareAuthorizationRequestResolver;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.support.PostgresIntegration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@PostgresIntegration
@SpringBootTest(
    properties = {
      "app.auth.google.enabled=true",
      "app.auth.google.registration-enabled=true",
      "app.auth.google.client-id=test-client",
      "app.auth.google.client-secret=test-secret",
      "app.auth.google.redirect-uri=http://localhost:5173/login/oauth2/code/google"
    })
@AutoConfigureMockMvc
class AuthenticationIntegrationTest {
  private static final String LINK_STATE_PREFIX = "pm1.link.";
  private static final String EXPECTED_OWNER_HEADER = "X-Expected-Owner-Id";
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
              .withDatabaseName("personal_memo_auth_test")
              .withUsername("personal_memo")
              .withPassword("test-only");
      postgres.start();
    }
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @Autowired AuthService auth;
  @Autowired AuthRepository authRepository;
  @Autowired SecurityContextHolderStrategy contextHolderStrategy;
  @Autowired SecurityContextRepository contextRepository;
  @Autowired ClientRegistrationRepository clientRegistrations;
  @Autowired FilterChainProxy springSecurityFilterChain;
  @Autowired CookieCsrfTokenRepository csrfTokenRepository;

  @BeforeEach
  void reset() {
    db.sql(
            "truncate table spring_session_attributes, spring_session, "
                + "idempotency_records, users cascade")
        .update();
    contextHolderStrategy.clearContext();
  }

  @AfterEach
  void clearSecurityContext() {
    contextHolderStrategy.clearContext();
  }

  @Test
  void anonymousDomainRequestIsJson401AndMissingCsrfIsJson403() throws Exception {
    MvcResult unauthenticated = mvc.perform(get("/api/v1/memos")).andReturn();
    assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(unauthenticated).path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");

    MvcResult unauthenticatedSearch =
        mvc.perform(
                csrfPost("/api/v1/search/memos", null)
                    .contentType("application/json")
                    .content("{\"query\":\"private\"}"))
            .andReturn();
    assertThat(unauthenticatedSearch.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(unauthenticatedSearch).path("code").asText())
        .isEqualTo("AUTHENTICATION_REQUIRED");

    MvcResult csrfFailure =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(registerBody("csrf@example.com"))))
            .andReturn();
    assertThat(csrfFailure.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(csrfFailure).path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");
  }

  @Test
  void anonymousLogoutRequiresCsrfAndIsIdempotentlySuccessfulWithAValidToken() throws Exception {
    MvcResult missingCsrf = mvc.perform(post("/api/v1/auth/logout")).andReturn();
    assertThat(missingCsrf.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(missingCsrf).path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");

    MvcResult firstLogout = mvc.perform(csrfPost("/api/v1/auth/logout", null)).andReturn();
    assertThat(firstLogout.getResponse().getStatus()).isEqualTo(204);

    MvcResult repeatedLogout = mvc.perform(csrfPost("/api/v1/auth/logout", null)).andReturn();
    assertThat(repeatedLogout.getResponse().getStatus()).isEqualTo(204);
  }

  @Test
  void csrfBootstrapCookieAndHeaderCreateAJdbcBackedSession() throws Exception {
    MvcResult csrfBootstrap = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
    assertThat(csrfBootstrap.getResponse().getStatus()).isEqualTo(200);
    JsonNode csrfBody = body(csrfBootstrap);
    Cookie csrfCookie = csrfBootstrap.getResponse().getCookie("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();
    assertThat(csrfCookie.isHttpOnly()).isFalse();

    MvcResult registered =
        mvc.perform(
                post("/api/v1/auth/register")
                    .cookie(csrfCookie)
                    .header(csrfBody.path("headerName").asText(), csrfBody.path("token").asText())
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(registerBody("bootstrap@example.com"))))
            .andReturn();
    assertThat(registered.getResponse().getStatus()).isEqualTo(201);
    assertThat(requireSessionCookie(registered).isHttpOnly()).isTrue();
    assertThat(db.sql("select count(*) from spring_session").query(Long.class).single())
        .isEqualTo(1);
  }

  @Test
  void localAuthenticationClearsOldCsrfIssuesFreshTokenAndIndexesTheRotatedSession()
      throws Exception {
    CsrfFilter csrfFilter =
        springSecurityFilterChain.getFilters("/api/v1/auth/csrf").stream()
            .filter(CsrfFilter.class::isInstance)
            .map(CsrfFilter.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(ReflectionTestUtils.getField(csrfFilter, "tokenRepository"))
        .isSameAs(csrfTokenRepository);
    ActualCsrf anonymousCsrf = actualCsrf(null);
    MvcResult registered =
        mvc.perform(
                post("/api/v1/auth/register")
                    .cookie(anonymousCsrf.cookie())
                    .header(anonymousCsrf.headerName(), anonymousCsrf.token())
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(registerBody("csrf-rotation@example.com"))))
            .andReturn();
    assertThat(registered.getResponse().getStatus()).isEqualTo(201);
    Cookie registeredSession = requireSessionCookie(registered);
    UUID userId = UUID.fromString(body(registered).path("userId").asText());
    assertClearedCookie(registered, "XSRF-TOKEN");
    assertThat(db.sql("select principal_name from spring_session").query(String.class).single())
        .isEqualTo(userId.toString());

    ActualCsrf registeredCsrf = actualCsrf(registeredSession);
    assertThat(registeredCsrf.token()).isNotEqualTo(anonymousCsrf.token());
    MvcResult loggedIn =
        mvc.perform(
                post("/api/v1/auth/login")
                    .cookie(registeredSession, registeredCsrf.cookie())
                    .header(registeredCsrf.headerName(), registeredCsrf.token())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "email",
                                "csrf-rotation@example.com",
                                "password",
                                "correct horse battery"))))
            .andReturn();
    assertThat(loggedIn.getResponse().getStatus()).isEqualTo(200);
    Cookie loggedInSession = requireSessionCookie(loggedIn);
    assertThat(loggedInSession.getValue()).isNotEqualTo(registeredSession.getValue());
    assertClearedCookie(loggedIn, "XSRF-TOKEN");

    ActualCsrf loggedInCsrf = actualCsrf(loggedInSession);
    assertThat(loggedInCsrf.token()).isNotEqualTo(registeredCsrf.token());
    assertThat(db.sql("select principal_name from spring_session").query(String.class).single())
        .isEqualTo(userId.toString());
    MvcResult protectedMutation =
        mvc.perform(
                post("/api/v1/auth/google/link-intent")
                    .cookie(loggedInSession, loggedInCsrf.cookie())
                    .header(loggedInCsrf.headerName(), loggedInCsrf.token()))
            .andReturn();
    assertThat(protectedMutation.getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void enabledGoogleCapabilityStartsAuthorizationWithoutCallingGoogle() throws Exception {
    MvcResult capabilities = mvc.perform(get("/api/v1/auth/capabilities")).andReturn();
    assertThat(capabilities.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(capabilities).path("googleEnabled").asBoolean()).isTrue();
    assertThat(body(capabilities).path("googleRegistrationEnabled").asBoolean()).isTrue();

    Cookie session =
        requireSessionCookie(register("oauth-start@example.com", "correct horse battery"));
    MvcResult intent =
        mvc.perform(csrfPost("/api/v1/auth/google/link-intent", session)).andReturn();
    assertThat(intent.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(intent).path("authorizationUrl").asText())
        .isEqualTo("/oauth2/authorization/google");

    MvcResult authorization =
        mvc.perform(get("/oauth2/authorization/google").cookie(session)).andReturn();
    assertThat(authorization.getResponse().getStatus()).isEqualTo(302);
    assertThat(authorization.getResponse().getRedirectedUrl())
        .startsWith("https://accounts.google.com/o/oauth2/v2/auth")
        .contains("client_id=test-client");
    ClientRegistration google = clientRegistrations.findByRegistrationId("google");
    assertThat(google.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    assertThat(google.getRedirectUri()).isEqualTo("http://localhost:5173/login/oauth2/code/google");
  }

  @Test
  void localReloginDiscardsAnotherOwnersGoogleLinkFlowBeforeInstallingTheNewOwner()
      throws Exception {
    MvcResult firstRegistration = register("link-boundary-a@example.com", "first secure password");
    Cookie firstSession = requireSessionCookie(firstRegistration);
    UUID firstOwner = UUID.fromString(body(firstRegistration).path("userId").asText());

    MvcResult intent =
        mvc.perform(csrfPost("/api/v1/auth/google/link-intent", firstSession)).andReturn();
    assertThat(intent.getResponse().getStatus()).isEqualTo(200);
    MvcResult authorization =
        mvc.perform(get("/oauth2/authorization/google").cookie(firstSession)).andReturn();
    String staleState =
        UriComponentsBuilder.fromUriString(authorization.getResponse().getRedirectedUrl())
            .build()
            .getQueryParams()
            .getFirst("state");
    assertThat(staleState).startsWith(LINK_STATE_PREFIX);

    var secondOwner =
        auth.register(
            "link-boundary-b@example.com", "second secure password", "Second Owner", "Asia/Seoul");
    MvcResult relogin =
        mvc.perform(
                csrfPost("/api/v1/auth/login", firstSession)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "email",
                                "link-boundary-b@example.com",
                                "password",
                                "second secure password"))))
            .andReturn();
    assertThat(relogin.getResponse().getStatus()).isEqualTo(200);
    Cookie secondSession = requireSessionCookie(relogin);
    assertThat(secondSession.getValue()).isNotEqualTo(firstSession.getValue());
    assertThat(body(relogin).path("userId").asText()).isEqualTo(secondOwner.userId().toString());
    assertThat(
            db.sql(
                    "select count(*) from spring_session_attributes "
                        + "where attribute_name like '%LINK_INTENT%' "
                        + "or attribute_name like '%AUTHORIZATION_REQUEST%'")
                .query(Long.class)
                .single())
        .isZero();

    assertThat(
            mvc.perform(get("/api/v1/auth/me").cookie(firstSession))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    assertThat(
            body(mvc.perform(get("/api/v1/auth/me").cookie(secondSession)).andReturn())
                .path("userId")
                .asText())
        .isEqualTo(secondOwner.userId().toString());

    MvcResult staleCallback =
        mvc.perform(
                get("/login/oauth2/code/google")
                    .cookie(secondSession)
                    .param("state", staleState)
                    .param("code", "stale-code"))
            .andReturn();
    assertThat(staleCallback.getResponse().getStatus()).isEqualTo(302);
    assertThat(staleCallback.getResponse().getRedirectedUrl())
        .isEqualTo("/account?error=LINK_INTENT_INVALID");
    MvcResult secondOwnerStillAuthenticated =
        mvc.perform(get("/api/v1/auth/me").cookie(secondSession)).andReturn();
    assertThat(secondOwnerStillAuthenticated.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(secondOwnerStillAuthenticated).path("userId").asText())
        .isEqualTo(secondOwner.userId().toString());
    assertThat(auth.loginMethods(firstOwner)).containsExactly(LoginMethod.LOCAL);
    assertThat(auth.loginMethods(secondOwner.userId())).containsExactly(LoginMethod.LOCAL);
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isZero();
  }

  @Test
  void invalidOrdinaryGoogleCallbackCannotLogOutAnExistingLocalSession() throws Exception {
    MvcResult registered = register("ordinary-callback@example.com", "correct horse battery");
    Cookie session = requireSessionCookie(registered);
    UUID ownerId = UUID.fromString(body(registered).path("userId").asText());

    MvcResult callback =
        mvc.perform(
                get("/login/oauth2/code/google")
                    .cookie(session)
                    .param("state", "unmarked-invalid-state")
                    .param("code", "invalid-code"))
            .andReturn();

    assertThat(callback.getResponse().getStatus()).isEqualTo(302);
    assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/account?error=OAUTH_FAILED");
    MvcResult stillAuthenticated = mvc.perform(get("/api/v1/auth/me").cookie(session)).andReturn();
    assertThat(stillAuthenticated.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(stillAuthenticated).path("userId").asText()).isEqualTo(ownerId.toString());
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isZero();
  }

  @Test
  void registerCreatesSessionMeLoginAndLogoutWithoutStoringRawPassword() throws Exception {
    String password = "correct horse battery";
    MvcResult registered = register("person@example.com", password);
    assertThat(registered.getResponse().getStatus()).isEqualTo(201);
    Cookie session = requireSessionCookie(registered);
    UUID userId = UUID.fromString(body(registered).path("userId").asText());
    assertThat(body(registered).path("loginMethods").toString()).contains("LOCAL");

    String storedHash =
        db.sql("select password_hash from local_credentials where user_id=:userId")
            .param("userId", userId)
            .query(String.class)
            .single();
    assertThat(storedHash).startsWith("{bcrypt}").isNotEqualTo(password);
    assertThat(db.sql("select count(*) from spring_session").query(Long.class).single())
        .isEqualTo(1);

    MvcResult me = mvc.perform(get("/api/v1/auth/me").cookie(session)).andReturn();
    assertThat(me.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(me).path("userId").asText()).isEqualTo(userId.toString());

    MvcResult logout = mvc.perform(csrfPost("/api/v1/auth/logout", session)).andReturn();
    assertThat(logout.getResponse().getStatus()).isEqualTo(204);
    assertThat(db.sql("select count(*) from spring_session").query(Long.class).single()).isZero();

    MvcResult loggedIn = login("person@example.com", password);
    assertThat(loggedIn.getResponse().getStatus()).isEqualTo(200);
    assertThat(requireSessionCookie(loggedIn).getValue()).isNotBlank();
  }

  @Test
  void repeatedLoginRotatesJdbcSessionAndInvalidatesTheOldIdentifier() throws Exception {
    Cookie oldSession =
        requireSessionCookie(register("rotation@example.com", "correct horse battery"));
    MvcResult relogin =
        mvc.perform(
                csrfPost("/api/v1/auth/login", oldSession)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "email",
                                "rotation@example.com",
                                "password",
                                "correct horse battery"))))
            .andReturn();
    assertThat(relogin.getResponse().getStatus()).isEqualTo(200);
    Cookie newSession = requireSessionCookie(relogin);
    assertThat(newSession.getValue()).isNotEqualTo(oldSession.getValue());

    assertThat(
            mvc.perform(get("/api/v1/auth/me").cookie(oldSession))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    assertThat(
            mvc.perform(get("/api/v1/auth/me").cookie(newSession))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void disabledAccountSessionsAreRejectedForMeAndDomainThenDeleted() throws Exception {
    MvcResult registered = register("disabled-session@example.com", "correct horse battery");
    Cookie meSession = requireSessionCookie(registered);
    UUID userId = UUID.fromString(body(registered).path("userId").asText());
    Cookie domainSession =
        requireSessionCookie(login("disabled-session@example.com", "correct horse battery"));
    Cookie logoutSession =
        requireSessionCookie(login("disabled-session@example.com", "correct horse battery"));
    assertThat(
            db.sql("select count(*) from spring_session where principal_name=:principal")
                .param("principal", userId.toString())
                .query(Long.class)
                .single())
        .isEqualTo(3);
    db.sql("update users set status='DISABLED',updated_at=now() where id=:userId")
        .param("userId", userId)
        .update();

    MvcResult disabledMe = mvc.perform(get("/api/v1/auth/me").cookie(meSession)).andReturn();
    assertThat(disabledMe.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(disabledMe).path("code").asText()).isEqualTo("ACCOUNT_DISABLED");
    assertClearedCookie(disabledMe, "SESSION");
    assertClearedCookie(disabledMe, "XSRF-TOKEN");
    assertThat(db.sql("select count(*) from spring_session").query(Long.class).single())
        .isEqualTo(2);

    MvcResult disabledDomain = mvc.perform(get("/api/v1/memos").cookie(domainSession)).andReturn();
    assertThat(disabledDomain.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(disabledDomain).path("code").asText()).isEqualTo("ACCOUNT_DISABLED");
    assertClearedCookie(disabledDomain, "SESSION");
    assertClearedCookie(disabledDomain, "XSRF-TOKEN");
    assertThat(db.sql("select count(*) from spring_session").query(Long.class).single())
        .isEqualTo(1);

    MvcResult disabledLogout =
        mvc.perform(csrfPost("/api/v1/auth/logout", logoutSession)).andReturn();
    assertThat(disabledLogout.getResponse().getStatus()).isEqualTo(204);
    assertThat(db.sql("select count(*) from spring_session").query(Long.class).single()).isZero();

    MvcResult invalidated = mvc.perform(get("/api/v1/auth/me").cookie(meSession)).andReturn();
    assertThat(invalidated.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(invalidated).path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
  }

  @Test
  void duplicateEmailAndLoginFailuresAreStableAndPasswordByteLimitIsEnforced() throws Exception {
    register("Case@Example.com", "correct horse battery");
    MvcResult duplicate = register("case@example.COM", "another secure password");
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(duplicate).path("code").asText()).isEqualTo("EMAIL_ALREADY_REGISTERED");

    MvcResult wrongPassword = login("case@example.com", "incorrect password");
    MvcResult missingAccount = login("missing@example.com", "incorrect password");
    assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
    assertThat(missingAccount.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(wrongPassword).path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(body(missingAccount).path("code").asText()).isEqualTo("INVALID_CREDENTIALS");

    MvcResult oversizedUtf8 = register("utf8@example.com", "가".repeat(25));
    assertThat(oversizedUtf8.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(oversizedUtf8).path("code").asText()).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void repeatedBadPasswordsTemporarilyLockTheLocalCredentialAndSuccessClearsTheCounter()
      throws Exception {
    MvcResult registered = register("lockout@example.com", "correct horse battery");
    UUID userId = UUID.fromString(body(registered).path("userId").asText());

    for (int attempt = 0; attempt < 5; attempt++) {
      MvcResult failure = login("lockout@example.com", "incorrect password");
      assertThat(failure.getResponse().getStatus()).isEqualTo(401);
      assertThat(body(failure).path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }
    assertThat(
            db.sql("select failed_attempts from local_credentials where user_id=:userId")
                .param("userId", userId)
                .query(Integer.class)
                .single())
        .isEqualTo(5);
    assertThat(
            db.sql("select locked_until > now() from local_credentials where user_id=:userId")
                .param("userId", userId)
                .query(Boolean.class)
                .single())
        .isTrue();

    MvcResult lockedCorrectPassword = login("lockout@example.com", "correct horse battery");
    assertThat(lockedCorrectPassword.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(lockedCorrectPassword).path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(
            db.sql("select failed_attempts from local_credentials where user_id=:userId")
                .param("userId", userId)
                .query(Integer.class)
                .single())
        .isEqualTo(5);

    db.sql(
            "update local_credentials set locked_until=now() - interval '1 second' where user_id=:userId")
        .param("userId", userId)
        .update();
    MvcResult afterExpiry = login("lockout@example.com", "correct horse battery");
    assertThat(afterExpiry.getResponse().getStatus()).isEqualTo(200);
    assertThat(
            db.sql(
                    "select failed_attempts=0 and locked_until is null "
                        + "from local_credentials where user_id=:userId")
                .param("userId", userId)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void authenticatedUsersSeeOnlyTheirOwnCanonicalData() throws Exception {
    Cookie first = requireSessionCookie(register("first@example.com", "first secure password"));
    Cookie second = requireSessionCookie(register("second@example.com", "second secure password"));
    UUID firstMemo = UUID.randomUUID();
    UUID secondMemo = UUID.randomUUID();

    assertThat(createMemo(first, firstMemo, "first-private").getResponse().getStatus())
        .isEqualTo(201);
    assertThat(createMemo(second, secondMemo, "second-private").getResponse().getStatus())
        .isEqualTo(201);
    MvcResult crossOwnerIdCollision = createMemo(second, firstMemo, "must-not-replace-first");
    assertThat(crossOwnerIdCollision.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(crossOwnerIdCollision).path("code").asText()).isEqualTo("MEMO_ID_CONFLICT");

    JsonNode firstList = body(mvc.perform(get("/api/v1/memos").cookie(first)).andReturn());
    JsonNode secondList = body(mvc.perform(get("/api/v1/memos").cookie(second)).andReturn());
    assertThat(firstList.toString()).contains("first-private").doesNotContain("second-private");
    assertThat(secondList.toString())
        .contains("second-private")
        .doesNotContain("first-private", "must-not-replace-first");
  }

  @Test
  void expectedOwnerHeaderBlocksCrossAccountReadsAndIdempotentWritesWithoutEndingSession()
      throws Exception {
    MvcResult firstRegistration = register("expected-first@example.com", "first secure password");
    UUID firstOwnerId = UUID.fromString(body(firstRegistration).path("userId").asText());
    MvcResult secondRegistration =
        register("expected-second@example.com", "second secure password");
    Cookie secondSession = requireSessionCookie(secondRegistration);
    UUID secondOwnerId = UUID.fromString(body(secondRegistration).path("userId").asText());

    MvcResult blockedRead =
        mvc.perform(
                get("/api/v1/memos")
                    .cookie(secondSession)
                    .header(EXPECTED_OWNER_HEADER, firstOwnerId))
            .andReturn();
    assertThat(blockedRead.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(blockedRead).path("code").asText()).isEqualTo("SESSION_OWNER_CHANGED");

    MvcResult blockedSearchBeforeBodyParsing =
        mvc.perform(
                csrfPost("/api/v1/search/memos", secondSession)
                    .header(EXPECTED_OWNER_HEADER, firstOwnerId)
                    .contentType("application/json")
                    .content("{"))
            .andReturn();
    assertThat(blockedSearchBeforeBodyParsing.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(blockedSearchBeforeBodyParsing).path("code").asText())
        .isEqualTo("SESSION_OWNER_CHANGED");

    MvcResult blockedReviewOutcomeRead =
        mvc.perform(
                get("/api/v1/analysis-review-outcomes/summary")
                    .cookie(secondSession)
                    .header(EXPECTED_OWNER_HEADER, firstOwnerId))
            .andReturn();
    assertThat(blockedReviewOutcomeRead.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(blockedReviewOutcomeRead).path("code").asText())
        .isEqualTo("SESSION_OWNER_CHANGED");

    UUID memoId = UUID.randomUUID();
    String idempotencyKey = "cross-account-create-" + memoId;
    MvcResult blockedWrite =
        mvc.perform(
                csrfPost("/api/v1/memos", secondSession)
                    .header(EXPECTED_OWNER_HEADER, firstOwnerId)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "id",
                                memoId,
                                "content",
                                "must-not-be-created",
                                "clientCreatedAt",
                                OffsetDateTime.parse("2026-08-05T11:00:00+09:00"),
                                "timeZone",
                                "Asia/Seoul"))))
            .andReturn();
    assertThat(blockedWrite.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(blockedWrite).path("code").asText()).isEqualTo("SESSION_OWNER_CHANGED");
    assertThat(db.sql("select count(*) from memos").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from idempotency_records").query(Long.class).single())
        .isZero();

    MvcResult malformed =
        mvc.perform(
                get("/api/v1/graph/home")
                    .cookie(secondSession)
                    .header(EXPECTED_OWNER_HEADER, "not-a-uuid"))
            .andReturn();
    assertThat(malformed.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(malformed).path("code").asText()).isEqualTo("SESSION_OWNER_CHANGED");

    MvcResult created =
        mvc.perform(
                csrfPost("/api/v1/memos", secondSession)
                    .header(EXPECTED_OWNER_HEADER, secondOwnerId)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "id",
                                memoId,
                                "content",
                                "second-owner-private",
                                "clientCreatedAt",
                                OffsetDateTime.parse("2026-08-05T11:00:00+09:00"),
                                "timeZone",
                                "Asia/Seoul"))))
            .andReturn();
    assertThat(created.getResponse().getStatus()).isEqualTo(201);

    MvcResult usableSession =
        mvc.perform(
                get("/api/v1/memos")
                    .cookie(secondSession)
                    .header(EXPECTED_OWNER_HEADER, secondOwnerId))
            .andReturn();
    assertThat(usableSession.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(usableSession).toString()).contains("second-owner-private");

    MvcResult usableSearch =
        mvc.perform(
                csrfPost("/api/v1/search/memos", secondSession)
                    .header(EXPECTED_OWNER_HEADER, secondOwnerId)
                    .contentType("application/json")
                    .content("{\"query\":\"second-owner-private\"}"))
            .andReturn();
    assertThat(usableSearch.getResponse().getStatus()).isEqualTo(200);
    assertThat(usableSearch.getResponse().getHeader("Cache-Control")).contains("no-store");
    assertThat(body(usableSearch).toString()).contains(memoId.toString(), "second-owner-private");
    assertThat(
            db.sql("select owner_id from memos where id=:memoId")
                .param("memoId", memoId)
                .query(UUID.class)
                .single())
        .isEqualTo(secondOwnerId);

    MvcResult blockedLogout =
        mvc.perform(
                csrfPost("/api/v1/auth/logout", secondSession)
                    .header(EXPECTED_OWNER_HEADER, firstOwnerId))
            .andReturn();
    assertThat(blockedLogout.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(blockedLogout).path("code").asText()).isEqualTo("SESSION_OWNER_CHANGED");
    assertThat(
            mvc.perform(get("/api/v1/auth/me").cookie(secondSession))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void newlyRegisteredOwnerCanReviewAndApplyOwnerNeutralFakeTagProposals() throws Exception {
    MvcResult registered = register("new-owner@example.com", "correct horse battery");
    Cookie session = requireSessionCookie(registered);
    UUID ownerId = UUID.fromString(body(registered).path("userId").asText());
    UUID memoId = UUID.randomUUID();
    String rawMemo = "2026.11.25 18:00 OS 과제 제출";

    assertThat(createMemo(session, memoId, rawMemo).getResponse().getStatus()).isEqualTo(201);
    MvcResult started =
        mvc.perform(
                csrfPost("/api/v1/memos/{id}/analysis-runs", session, memoId)
                    .header("Idempotency-Key", "new-owner-analysis-start")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("memoRevision", 1, "policy", "AUTO"))))
            .andReturn();

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    UUID proposalId = UUID.fromString(body(started).path("proposalId").asText());
    MvcResult proposal =
        mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId).cookie(session)).andReturn();
    assertThat(proposal.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(proposal).path("tagCandidates")).hasSize(2);
    assertThat(body(proposal).at("/tagCandidates/0/existingTagId").isNull()).isTrue();
    assertThat(body(proposal).at("/tagCandidates/0/isNewProposal").asBoolean()).isTrue();
    assertThat(body(proposal).at("/tagCandidates/1/existingTagId").isNull()).isTrue();
    assertThat(body(proposal).at("/tagCandidates/1/isNewProposal").asBoolean()).isTrue();
    assertThat(body(proposal).toString())
        .doesNotContain("10000000-0000-0000-0000-000000000001")
        .doesNotContain("10000000-0000-0000-0000-000000000002");

    Map<String, Object> item = new java.util.LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", "OS 과제 제출");
    item.put("due", null);
    Map<String, Object> applyBody =
        Map.of(
            "expectedMemoRevision",
            1,
            "selectedType",
            "TASK",
            "title",
            "OS 과제 제출",
            "selectedTags",
            List.of(Map.of("newCanonicalName", "운영체제"), Map.of("newCanonicalName", "과제")),
            "items",
            List.of(item));
    MvcResult applied =
        mvc.perform(
                csrfPost("/api/v1/analysis-proposals/{id}/apply", session, proposalId)
                    .header("Idempotency-Key", "new-owner-analysis-apply")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(applyBody)))
            .andReturn();

    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(applied).path("status").asText()).isEqualTo("APPLIED");
    assertThat(
            db.sql(
                    "select canonical_name from tags where owner_id=:ownerId order by canonical_name")
                .param("ownerId", ownerId)
                .query(String.class)
                .list())
        .containsExactly("과제", "운영체제");
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:memoId and owner_id=:ownerId")
                .param("memoId", memoId)
                .param("ownerId", ownerId)
                .query(String.class)
                .single())
        .isEqualTo(rawMemo);
    assertThat(
            db.sql("select count(*) from memo_items where memo_id=:memoId and owner_id=:ownerId")
                .param("memoId", memoId)
                .param("ownerId", ownerId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void googleNeverAutoLinksByEmailButExplicitLinkAndSafeUnlinkWork() {
    var local = auth.register("owner@example.com", "correct horse battery", "Owner", "Asia/Seoul");
    GoogleProfile sameEmailGoogle =
        new GoogleProfile("google-owner", "OWNER@example.com", true, "Google Owner");

    assertThatThrownBy(() -> auth.googleLogin(sameEmailGoogle))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo("ACCOUNT_LINK_REQUIRED");
              assertThat(exception.status().value()).isEqualTo(409);
            });
    assertThat(db.sql("select count(*) from users").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isZero();

    auth.linkGoogle(local.userId(), sameEmailGoogle);
    assertThat(auth.loginMethods(local.userId()))
        .containsExactlyInAnyOrder(LoginMethod.LOCAL, LoginMethod.GOOGLE);
    assertThat(authRepository.unlinkGoogle(local.userId(), "another-subject")).isFalse();
    assertThat(auth.loginMethods(local.userId()))
        .containsExactlyInAnyOrder(LoginMethod.LOCAL, LoginMethod.GOOGLE);
    auth.unlinkGoogle(local.userId());
    assertThat(auth.loginMethods(local.userId())).containsExactly(LoginMethod.LOCAL);
  }

  @Test
  void googleSubjectIsStableConflictsAreRejectedAndLastMethodCannotBeRemoved() {
    GoogleProfile google =
        new GoogleProfile("stable-google-sub", "google@example.com", true, "Google User");
    var googleUser = auth.googleLogin(google);
    assertThat(auth.googleLogin(google).userId()).isEqualTo(googleUser.userId());
    assertThat(
            authRepository.touchGoogleLogin(
                "missing-google-subject", "missing@example.com", Instant.now()))
        .isFalse();

    var local = auth.register("local@example.com", "correct horse battery", "Local", "Asia/Seoul");
    assertThatThrownBy(() -> auth.linkGoogle(local.userId(), google))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("GOOGLE_IDENTITY_CONFLICT"));
    assertThatThrownBy(() -> auth.unlinkGoogle(googleUser.userId()))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("LOGIN_METHOD_REQUIRED"));
  }

  @Test
  void concurrentGoogleLoginAndUnlinkProduceOnlySerializedOutcomes() throws Exception {
    var local =
        auth.register(
            "concurrent@example.com", "correct horse battery", "Concurrent", "Asia/Seoul");
    GoogleProfile google =
        new GoogleProfile("concurrent-sub", "concurrent@example.com", true, "Concurrent Google");
    auth.linkGoogle(local.userId(), google);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var login =
          executor.submit(
              () -> {
                start.await();
                try {
                  return (Object) auth.googleLogin(google);
                } catch (DomainException exception) {
                  return exception;
                }
              });
      var unlink =
          executor.submit(
              () -> {
                start.await();
                auth.unlinkGoogle(local.userId());
                return null;
              });

      start.countDown();
      Object loginOutcome = login.get(10, TimeUnit.SECONDS);
      unlink.get(10, TimeUnit.SECONDS);

      if (loginOutcome instanceof DomainException exception) {
        assertThat(exception.code()).isEqualTo("ACCOUNT_LINK_REQUIRED");
      } else {
        assertThat(loginOutcome)
            .isInstanceOfSatisfying(
                local.personalmemo.auth.domain.AppPrincipal.class,
                principal -> assertThat(principal.userId()).isEqualTo(local.userId()));
      }
      assertThat(db.sql("select count(*) from users").query(Long.class).single()).isEqualTo(1);
      assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
          .isZero();
      assertThat(auth.loginMethods(local.userId())).containsExactly(LoginMethod.LOCAL);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void googleRequiresVerifiedEmailAndFlywayOwnsSessionSchema() {
    assertThatThrownBy(
            () ->
                auth.googleLogin(
                    new GoogleProfile("unverified", "unverified@example.com", false, "No")))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("GOOGLE_EMAIL_NOT_VERIFIED"));
    assertThat(
            db.sql(
                    "select count(*) from information_schema.tables "
                        + "where table_schema=current_schema() "
                        + "and table_name in ('spring_session','spring_session_attributes')")
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void oauthHandlerUsesSubRemovesProviderTokenAndInstallsInternalPrincipal() throws Exception {
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler handler = oauthSuccessHandler(authorizedClients);
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = (MockHttpSession) request.getSession(true);
    MockHttpServletResponse response = new MockHttpServletResponse();
    OAuth2AuthenticationToken oauth =
        oauthToken("normal-sub", "normal-google@example.com", true, "Normal Google");

    handler.onAuthenticationSuccess(request, response, oauth);

    assertThat(response.getRedirectedUrl()).isEqualTo("/");
    assertThat(contextHolderStrategy.getContext().getAuthentication().getPrincipal())
        .isInstanceOfSatisfying(
            local.personalmemo.auth.domain.AppPrincipal.class,
            principal -> assertThat(principal.email()).isEqualTo("normal-google@example.com"));
    String userId =
        db.sql("select user_id from external_identities where provider_subject='normal-sub'")
            .query(UUID.class)
            .single()
            .toString();
    assertThat(session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
        .isEqualTo(userId);
    verify(authorizedClients).removeAuthorizedClient("google", "normal-sub");
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isEqualTo(1);
  }

  @Test
  void oauthLinkRequiresMatchingStateAndRestoresOriginalOwnerOnMismatch() throws Exception {
    var local =
        auth.register("link@example.com", "correct horse battery", "Link Owner", "Asia/Seoul");
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler handler = oauthSuccessHandler(authorizedClients);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("state", linkState("late-or-parallel-state"));
    request
        .getSession(true)
        .setAttribute(
            GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
            new GoogleOAuthSuccessHandler.LinkIntent(
                local.userId(), Instant.now(), linkState("expected-state")));
    indexOwner(request, local.userId());
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(
        request, response, oauthToken("unlinked-sub", "link@example.com", true, "Google Link"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/account?error=LINK_INTENT_INVALID");
    assertThat(auth.loginMethods(local.userId())).containsExactly(LoginMethod.LOCAL);
    assertThat(contextHolderStrategy.getContext().getAuthentication().getPrincipal())
        .isInstanceOfSatisfying(
            local.personalmemo.auth.domain.AppPrincipal.class,
            principal -> assertThat(principal.userId()).isEqualTo(local.userId()));
    verify(authorizedClients).removeAuthorizedClient("google", "unlinked-sub");
  }

  @Test
  void oauthLinkRequiresAnIndexedSessionOwnerBeforeMutating() throws Exception {
    var local =
        auth.register(
            "missing-index@example.com", "correct horse battery", "Missing Index", "Asia/Seoul");
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler handler = oauthSuccessHandler(authorizedClients);
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = (MockHttpSession) request.getSession(true);
    String state = linkState("missing-owner-index");
    request.setParameter("state", state);
    session.setAttribute(
        GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
        new GoogleOAuthSuccessHandler.LinkIntent(local.userId(), Instant.now(), state));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(
        request,
        response,
        oauthToken("missing-index-sub", "missing-index@example.com", true, "Missing Index"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/account?error=LINK_INTENT_INVALID");
    assertThat(auth.loginMethods(local.userId())).containsExactly(LoginMethod.LOCAL);
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isZero();
    assertThatThrownBy(() -> session.getAttribute("anything"))
        .isInstanceOf(IllegalStateException.class);
    verify(authorizedClients).removeAuthorizedClient("google", "missing-index-sub");
  }

  @Test
  void oauthLinkRejectsAMismatchedIntentAndKeepsTheIndexedOwner() throws Exception {
    var staleOwner =
        auth.register(
            "stale-owner@example.com", "correct horse battery", "Stale Owner", "Asia/Seoul");
    var currentOwner =
        auth.register(
            "current-owner@example.com", "correct horse battery", "Current Owner", "Asia/Seoul");
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler handler = oauthSuccessHandler(authorizedClients);
    MockHttpServletRequest request = new MockHttpServletRequest();
    String state = linkState("stale-owner-intent");
    request.setParameter("state", state);
    request
        .getSession(true)
        .setAttribute(
            GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
            new GoogleOAuthSuccessHandler.LinkIntent(staleOwner.userId(), Instant.now(), state));
    indexOwner(request, currentOwner.userId());
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(
        request,
        response,
        oauthToken("stale-owner-sub", "stale-owner@example.com", true, "Stale Owner"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/account?error=LINK_INTENT_INVALID");
    assertThat(auth.loginMethods(staleOwner.userId())).containsExactly(LoginMethod.LOCAL);
    assertThat(auth.loginMethods(currentOwner.userId())).containsExactly(LoginMethod.LOCAL);
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isZero();
    assertThat(contextHolderStrategy.getContext().getAuthentication().getPrincipal())
        .isInstanceOfSatisfying(
            local.personalmemo.auth.domain.AppPrincipal.class,
            principal -> assertThat(principal.userId()).isEqualTo(currentOwner.userId()));
    verify(authorizedClients).removeAuthorizedClient("google", "stale-owner-sub");
  }

  @Test
  void oauthMatchingLinkStateLinksAndProviderFailureRestoresLocalSession() throws Exception {
    var local =
        auth.register("explicit@example.com", "correct horse battery", "Explicit", "Asia/Seoul");
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler successHandler = oauthSuccessHandler(authorizedClients);
    MockHttpServletRequest successRequest = new MockHttpServletRequest();
    successRequest.setParameter("state", linkState("bound-state"));
    successRequest
        .getSession(true)
        .setAttribute(
            GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
            new GoogleOAuthSuccessHandler.LinkIntent(
                local.userId(), Instant.now(), linkState("bound-state")));
    indexOwner(successRequest, local.userId());
    MockHttpServletResponse successResponse = new MockHttpServletResponse();

    successHandler.onAuthenticationSuccess(
        successRequest,
        successResponse,
        oauthToken("explicit-sub", "explicit@example.com", true, "Explicit Google"));
    assertThat(successResponse.getRedirectedUrl()).isEqualTo("/account?linked=google");
    assertThat(auth.loginMethods(local.userId()))
        .containsExactlyInAnyOrder(LoginMethod.LOCAL, LoginMethod.GOOGLE);

    GoogleOAuthFailureHandler failureHandler =
        new GoogleOAuthFailureHandler(auth, contextHolderStrategy, contextRepository);
    contextHolderStrategy.clearContext();
    MockHttpServletRequest failedRequest = new MockHttpServletRequest();
    failedRequest.setParameter("state", linkState("another-state"));
    failedRequest
        .getSession(true)
        .setAttribute(
            GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
            new GoogleOAuthSuccessHandler.LinkIntent(
                local.userId(), Instant.now(), linkState("another-state")));
    indexOwner(failedRequest, local.userId());
    MockHttpServletResponse failedResponse = new MockHttpServletResponse();
    failureHandler.onAuthenticationFailure(
        failedRequest,
        failedResponse,
        new OAuth2AuthenticationException(new OAuth2Error("provider_error")));
    assertThat(failedResponse.getRedirectedUrl()).isEqualTo("/account?error=OAUTH_FAILED");
    assertThat(contextHolderStrategy.getContext().getAuthentication().getPrincipal())
        .isInstanceOfSatisfying(
            local.personalmemo.auth.domain.AppPrincipal.class,
            principal -> assertThat(principal.userId()).isEqualTo(local.userId()));
    Object storedContext = failedRequest.getSession(false).getAttribute("SPRING_SECURITY_CONTEXT");
    assertThat(storedContext)
        .isInstanceOfSatisfying(
            org.springframework.security.core.context.SecurityContext.class,
            context ->
                assertThat(context.getAuthentication().getPrincipal())
                    .isInstanceOfSatisfying(
                        local.personalmemo.auth.domain.AppPrincipal.class,
                        principal -> assertThat(principal.userId()).isEqualTo(local.userId())));
  }

  @Test
  void authorizationResolverBindsOnlyTheFirstGeneratedStateToLinkIntent() {
    LinkAwareAuthorizationRequestResolver resolver =
        new LinkAwareAuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(testGoogleRegistration()));
    UUID ownerId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.setRequestURI("/oauth2/authorization/google");
    request.setServletPath("/oauth2/authorization/google");
    request
        .getSession(true)
        .setAttribute(
            GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
            new GoogleOAuthSuccessHandler.LinkIntent(ownerId, Instant.now(), null));

    var firstAuthorization = resolver.resolve(request);
    assertThat(firstAuthorization).isNotNull();
    var boundIntent =
        (GoogleOAuthSuccessHandler.LinkIntent)
            request
                .getSession(false)
                .getAttribute(GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE);
    assertThat(boundIntent.ownerId()).isEqualTo(ownerId);
    assertThat(boundIntent.oauthState()).isEqualTo(firstAuthorization.getState());
    assertThat(firstAuthorization.getState()).startsWith(LINK_STATE_PREFIX);
    assertThat(firstAuthorization.getAuthorizationRequestUri())
        .contains("state=" + LINK_STATE_PREFIX);

    var secondAuthorization = resolver.resolve(request);
    assertThat(secondAuthorization).isNotNull();
    var stillBoundToFirst =
        (GoogleOAuthSuccessHandler.LinkIntent)
            request
                .getSession(false)
                .getAttribute(GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE);
    assertThat(stillBoundToFirst.oauthState()).isEqualTo(firstAuthorization.getState());
    assertThat(secondAuthorization.getState()).isNotEqualTo(firstAuthorization.getState());
    assertThat(secondAuthorization.getState()).startsWith(LINK_STATE_PREFIX);
  }

  @Test
  void authorizationResolverLeavesOrdinaryLoginStateUnmarked() {
    LinkAwareAuthorizationRequestResolver resolver =
        new LinkAwareAuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(testGoogleRegistration()));
    MockHttpServletRequest request = oauthAuthorizationRequest();
    request.getSession(true);

    var authorization = resolver.resolve(request);

    assertThat(authorization).isNotNull();
    assertThat(authorization.getState()).doesNotStartWith(LINK_STATE_PREFIX);
    assertThat(authorization.getAuthorizationRequestUri())
        .doesNotContain("state=" + LINK_STATE_PREFIX);
  }

  @Test
  void markedLinkWithoutIntentNeverFallsBackToOrdinaryGoogleLogin() throws Exception {
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler handler = oauthSuccessHandler(authorizedClients);
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = (MockHttpSession) request.getSession(true);
    request.setParameter("state", linkState("missing-intent"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(
        request,
        response,
        oauthToken("missing-intent-sub", "new-google@example.com", true, "New Google"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/account?error=LINK_INTENT_INVALID");
    assertThat(db.sql("select count(*) from users").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isZero();
    assertThatThrownBy(() -> session.getAttribute("anything"))
        .isInstanceOf(IllegalStateException.class);
    verify(authorizedClients).removeAuthorizedClient("google", "missing-intent-sub");
  }

  @Test
  void expiredLinkIntentIsRejectedWithoutGoogleLoginFallback() throws Exception {
    var local =
        auth.register("expired@example.com", "correct horse battery", "Expired", "Asia/Seoul");
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler handler = oauthSuccessHandler(authorizedClients);
    MockHttpServletRequest request = new MockHttpServletRequest();
    String state = linkState("expired-intent");
    request.setParameter("state", state);
    request
        .getSession(true)
        .setAttribute(
            GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
            new GoogleOAuthSuccessHandler.LinkIntent(
                local.userId(), Instant.now().minus(Duration.ofMinutes(11)), state));
    indexOwner(request, local.userId());
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(
        request, response, oauthToken("expired-sub", "brand-new@example.com", true, "Brand New"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/account?error=LINK_INTENT_EXPIRED");
    assertThat(auth.loginMethods(local.userId())).containsExactly(LoginMethod.LOCAL);
    assertThat(db.sql("select count(*) from users").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isZero();
  }

  @Test
  void replayedMarkedLinkCallbackCannotBecomeOrdinaryLogin() throws Exception {
    var local =
        auth.register("replay@example.com", "correct horse battery", "Replay", "Asia/Seoul");
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler handler = oauthSuccessHandler(authorizedClients);
    MockHttpSession session = new MockHttpSession();
    String state = linkState("one-time-state");
    OAuth2AuthenticationToken oauth =
        oauthToken("replay-sub", "replay@example.com", true, "Replay Google");

    MockHttpServletRequest firstRequest = new MockHttpServletRequest();
    firstRequest.setSession(session);
    firstRequest.setParameter("state", state);
    session.setAttribute(
        GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
        new GoogleOAuthSuccessHandler.LinkIntent(local.userId(), Instant.now(), state));
    session.setAttribute(
        FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, local.userId().toString());
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(firstRequest, firstResponse, oauth);
    assertThat(firstResponse.getRedirectedUrl()).isEqualTo("/account?linked=google");

    MockHttpServletRequest replayRequest = new MockHttpServletRequest();
    replayRequest.setSession(session);
    replayRequest.setParameter("state", state);
    MockHttpServletResponse replayResponse = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(replayRequest, replayResponse, oauth);

    assertThat(replayResponse.getRedirectedUrl()).isEqualTo("/account?error=LINK_INTENT_INVALID");
    assertThat(db.sql("select count(*) from users").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isEqualTo(1);
    verify(authorizedClients, times(2)).removeAuthorizedClient("google", "replay-sub");
  }

  @Test
  void twoTabLinkCallbacksCannotConsumeIntentThenFallBackToLogin() throws Exception {
    var local =
        auth.register("two-tab@example.com", "correct horse battery", "Two Tab", "Asia/Seoul");
    LinkAwareAuthorizationRequestResolver resolver =
        new LinkAwareAuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(testGoogleRegistration()));
    MockHttpServletRequest authorizationRequest = oauthAuthorizationRequest();
    MockHttpSession session = (MockHttpSession) authorizationRequest.getSession(true);
    session.setAttribute(
        GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
        new GoogleOAuthSuccessHandler.LinkIntent(local.userId(), Instant.now(), null));
    session.setAttribute(
        FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, local.userId().toString());
    var firstAuthorization = resolver.resolve(authorizationRequest);
    var secondAuthorization = resolver.resolve(authorizationRequest);
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler handler = oauthSuccessHandler(authorizedClients);
    OAuth2AuthenticationToken oauth =
        oauthToken("two-tab-sub", "different@example.com", true, "Different Google");

    MockHttpServletRequest secondCallback = new MockHttpServletRequest();
    secondCallback.setSession(session);
    secondCallback.setParameter("state", secondAuthorization.getState());
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(secondCallback, secondResponse, oauth);
    assertThat(secondResponse.getRedirectedUrl()).isEqualTo("/account?error=LINK_INTENT_INVALID");

    MockHttpServletRequest firstCallback = new MockHttpServletRequest();
    firstCallback.setSession(session);
    firstCallback.setParameter("state", firstAuthorization.getState());
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(firstCallback, firstResponse, oauth);

    assertThat(firstResponse.getRedirectedUrl()).isEqualTo("/account?error=LINK_INTENT_INVALID");
    assertThat(db.sql("select count(*) from users").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isZero();
  }

  @Test
  void markedProviderFailureWithoutIntentStaysInLinkFailureFlow() throws Exception {
    GoogleOAuthFailureHandler handler =
        new GoogleOAuthFailureHandler(auth, contextHolderStrategy, contextRepository);
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = (MockHttpSession) request.getSession(true);
    request.setParameter("state", linkState("missing-on-failure"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationFailure(
        request, response, new OAuth2AuthenticationException(new OAuth2Error("provider_error")));

    assertThat(response.getRedirectedUrl()).isEqualTo("/account?error=LINK_INTENT_INVALID");
    assertThatThrownBy(() -> session.getAttribute("anything"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void oauthEmailConflictDoesNotLeaveOidcAuthenticationInSession() throws Exception {
    auth.register("collision@example.com", "correct horse battery", "Collision", "Asia/Seoul");
    OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    GoogleOAuthSuccessHandler handler = oauthSuccessHandler(authorizedClients);
    MockHttpServletRequest request = new MockHttpServletRequest();
    var session = request.getSession(true);
    OAuth2AuthenticationToken oauth =
        oauthToken("collision-sub", "collision@example.com", true, "Collision Google");
    var oidcContext = contextHolderStrategy.createEmptyContext();
    oidcContext.setAuthentication(oauth);
    contextHolderStrategy.setContext(oidcContext);
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, oauth);

    assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=ACCOUNT_LINK_REQUIRED");
    assertThat(contextHolderStrategy.getContext().getAuthentication()).isNull();
    assertThatThrownBy(() -> session.getAttribute("anything"))
        .isInstanceOf(IllegalStateException.class);
    verify(authorizedClients).removeAuthorizedClient("google", "collision-sub");
  }

  private MvcResult register(String email, String password) throws Exception {
    return mvc.perform(
            csrfPost("/api/v1/auth/register", null)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "email",
                            email,
                            "password",
                            password,
                            "displayName",
                            "Test User",
                            "timeZone",
                            "Asia/Seoul"))))
        .andReturn();
  }

  private GoogleOAuthSuccessHandler oauthSuccessHandler(
      OAuth2AuthorizedClientService authorizedClients) {
    return new GoogleOAuthSuccessHandler(
        auth, contextHolderStrategy, contextRepository, authorizedClients);
  }

  private OAuth2AuthenticationToken oauthToken(
      String subject, String email, boolean verified, String fullName) {
    OidcUser oidc = mock(OidcUser.class);
    when(oidc.getSubject()).thenReturn(subject);
    when(oidc.getEmail()).thenReturn(email);
    when(oidc.getFullName()).thenReturn(fullName);
    when(oidc.getName()).thenReturn(subject);
    when(oidc.getClaims()).thenReturn(Map.<String, Object>of("email_verified", verified));
    return new OAuth2AuthenticationToken(
        oidc, List.of(new SimpleGrantedAuthority("OIDC_USER")), "google");
  }

  private MockHttpServletRequest oauthAuthorizationRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.setRequestURI("/oauth2/authorization/google");
    request.setServletPath("/oauth2/authorization/google");
    return request;
  }

  private String linkState(String state) {
    return LINK_STATE_PREFIX + state;
  }

  private void indexOwner(MockHttpServletRequest request, UUID ownerId) {
    request
        .getSession(true)
        .setAttribute(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, ownerId.toString());
  }

  private ClientRegistration testGoogleRegistration() {
    return ClientRegistration.withRegistrationId("google")
        .clientId("test-client")
        .clientSecret("test-secret")
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid", "profile", "email")
        .authorizationUri("https://accounts.example.test/authorize")
        .tokenUri("https://accounts.example.test/token")
        .jwkSetUri("https://accounts.example.test/keys")
        .userInfoUri("https://accounts.example.test/userinfo")
        .userNameAttributeName("sub")
        .clientName("Google test")
        .build();
  }

  private MvcResult login(String email, String password) throws Exception {
    return mvc.perform(
            csrfPost("/api/v1/auth/login", null)
                .contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("email", email, "password", password))))
        .andReturn();
  }

  private MvcResult createMemo(Cookie session, UUID memoId, String content) throws Exception {
    return mvc.perform(
            csrfPost("/api/v1/memos", session)
                .header("Idempotency-Key", "create-" + memoId)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "id",
                            memoId,
                            "content",
                            content,
                            "clientCreatedAt",
                            OffsetDateTime.parse("2026-08-05T11:00:00+09:00"),
                            "timeZone",
                            "Asia/Seoul"))))
        .andReturn();
  }

  private MockHttpServletRequestBuilder csrfPost(
      String path, Cookie session, Object... uriVariables) throws Exception {
    ActualCsrf csrf = actualCsrf(session);
    MockHttpServletRequestBuilder request = post(path, uriVariables);
    if (session == null) {
      request.cookie(csrf.cookie());
    } else {
      request.cookie(session, csrf.cookie());
    }
    return request.header(csrf.headerName(), csrf.token());
  }

  private ActualCsrf actualCsrf(Cookie session) throws Exception {
    var request = get("/api/v1/auth/csrf");
    if (session != null) {
      request.cookie(session);
    }
    MvcResult result = mvc.perform(request).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    JsonNode response = body(result);
    Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
    assertThat(cookie).isNotNull();
    return new ActualCsrf(
        cookie, response.path("headerName").asText(), response.path("token").asText());
  }

  private void assertClearedCookie(MvcResult result, String name) {
    Cookie cookie = result.getResponse().getCookie(name);
    assertThat(cookie).as(name + " deletion cookie").isNotNull();
    assertThat(cookie.getMaxAge()).isZero();
    assertThat(cookie.getValue()).isNullOrEmpty();
  }

  private Map<String, Object> registerBody(String email) {
    return Map.of(
        "email", email,
        "password", "correct horse battery",
        "displayName", "Test User",
        "timeZone", "Asia/Seoul");
  }

  private Cookie requireSessionCookie(MvcResult result) {
    Cookie session = result.getResponse().getCookie("SESSION");
    assertThat(session).as("server-managed session cookie").isNotNull();
    return session;
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
