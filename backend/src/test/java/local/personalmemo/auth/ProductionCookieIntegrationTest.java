package local.personalmemo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import local.personalmemo.auth.infrastructure.AuthRepository;
import local.personalmemo.support.PostgresIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@PostgresIntegration
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductionCookieIntegrationTest {
  private static PostgreSQLContainer postgres;

  @LocalServerPort int port;
  @Autowired JdbcClient db;
  @Autowired AuthRepository authRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired ObjectMapper json;

  @BeforeEach
  void reset() {
    db.sql(
            "truncate table spring_session_attributes, spring_session, "
                + "idempotency_records, users cascade")
        .update();
  }

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
              .withDatabaseName("personal_memo_prod_cookie_test")
              .withUsername("personal_memo")
              .withPassword("test-only");
      postgres.start();
    }
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Test
  void csrfBootstrapUsesAnExplicitSecureSameSiteCookieInProduction() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
            .GET()
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues("Set-Cookie"))
        .anySatisfy(
            header -> {
              assertThat(header).startsWith("XSRF-TOKEN=");
              assertThat(header).contains("Path=/");
              assertThat(header).contains("; Secure");
              assertThat(header).doesNotContain("HttpOnly");
              assertThat(header).contains("SameSite=Lax");
            });
  }

  @Test
  void localRegistrationIsRejectedWithoutCreatingAUserInProduction() throws Exception {
    String email = "production-registration@example.com";
    CsrfExchange csrf = csrfExchange();
    HttpResponse<String> response =
        postJson(
            "/api/v1/auth/register",
            """
            {
              "email": "production-registration@example.com",
              "password": "correct-horse-battery-staple",
              "displayName": "Production user",
              "timeZone": "Asia/Seoul"
            }
            """,
            csrf);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(json.readTree(response.body()).path("code").asText())
        .isEqualTo("REGISTRATION_DISABLED");
    assertThat(authRepository.findUserByNormalizedEmail(email)).isEmpty();
  }

  @Test
  void localLoginUsesASecureHttpOnlySameSiteSessionCookieInProduction() throws Exception {
    String email = "production-login@example.com";
    String password = "correct-horse-battery-staple";
    authRepository.createLocalUser(
        UUID.randomUUID(),
        email,
        email,
        "Production user",
        passwordEncoder.encode(password),
        "Asia/Seoul",
        Instant.now());
    CsrfExchange csrf = csrfExchange();

    HttpResponse<String> response =
        postJson(
            "/api/v1/auth/login",
            """
            {
              "email": "production-login@example.com",
              "password": "correct-horse-battery-staple"
            }
            """,
            csrf);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues("Set-Cookie"))
        .anySatisfy(
            header -> {
              assertThat(header).startsWith("SESSION=");
              assertThat(header).contains("Path=/");
              assertThat(header).contains("; Secure");
              assertThat(header).contains("; HttpOnly");
              assertThat(header).contains("SameSite=Lax");
            });
  }

  private CsrfExchange csrfExchange() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/auth/csrf")).GET().build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(response.body());
    String cookie =
        response.headers().allValues("Set-Cookie").stream()
            .filter(header -> header.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow()
            .split(";", 2)[0];
    return new CsrfExchange(body.path("headerName").asText(), body.path("token").asText(), cookie);
  }

  private HttpResponse<String> postJson(String path, String body, CsrfExchange csrf)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .header("Cookie", csrf.cookie())
            .header(csrf.headerName(), csrf.token())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + port;
  }

  private static String environmentOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private record CsrfExchange(String headerName, String token, String cookie) {}
}
