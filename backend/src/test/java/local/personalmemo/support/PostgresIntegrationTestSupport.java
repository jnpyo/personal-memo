package local.personalmemo.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class PostgresIntegrationTestSupport {

  protected static final UUID OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  protected static final UUID OPERATING_SYSTEMS_TAG_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  protected static final UUID ASSIGNMENT_TAG_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000002");

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
              .withDatabaseName("personal_memo_test")
              .withUsername("personal_memo")
              .withPassword("test-only");
      postgres.start();
    }
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;
  @Autowired protected JdbcClient db;

  @BeforeEach
  void resetMutableData() {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-05T02:00:00Z"));
    db.sql("truncate table idempotency_records, users cascade").update();
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:id,'Asia/Seoul',false)")
        .param("id", OWNER_ID)
        .update();
    db.sql(
            "insert into tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at) "
                + "values(:os,:owner,'운영체제','운영체제','ACTIVE',:now,:now),"
                + "(:assignment,:owner,'과제','과제','ACTIVE',:now,:now)")
        .param("os", OPERATING_SYSTEMS_TAG_ID)
        .param("assignment", ASSIGNMENT_TAG_ID)
        .param("owner", OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into tag_aliases(id,owner_id,tag_id,alias,normalized_alias,source,created_at) "
                + "values('20000000-0000-0000-0000-000000000001',:owner,:tag,'OS','os','USER',:now)")
        .param("owner", OWNER_ID)
        .param("tag", OPERATING_SYSTEMS_TAG_ID)
        .param("now", now)
        .update();
  }

  protected MvcResult createMemo(UUID memoId, String key, String content) throws Exception {
    var body =
        Map.of(
            "id", memoId,
            "content", content,
            "clientCreatedAt", OffsetDateTime.parse("2026-08-05T11:00:00+09:00"),
            "timeZone", "Asia/Seoul");
    return mvc.perform(
            post("/api/v1/memos")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(json.writeValueAsBytes(body)))
        .andReturn();
  }

  protected MvcResult updateMemo(UUID memoId, int expectedRevision, String content)
      throws Exception {
    return mvc.perform(
            patch("/api/v1/memos/{id}", memoId)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("expectedRevision", expectedRevision, "content", content))))
        .andReturn();
  }

  protected MvcResult startAnalysis(UUID memoId, String key, int memoRevision) throws Exception {
    return mvc.perform(
            post("/api/v1/memos/{id}/analysis-runs", memoId)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("memoRevision", memoRevision, "policy", "AUTO"))))
        .andReturn();
  }

  protected MvcResult applyProposal(
      UUID proposalId, String key, int expectedRevision, String title, Object due)
      throws Exception {
    Map<String, Object> item = new java.util.LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", title);
    item.put("due", due);
    var body =
        Map.of(
            "expectedMemoRevision", expectedRevision,
            "selectedType", "TASK",
            "title", title,
            "selectedTags",
                java.util.List.of(Map.of("existingTagId", OPERATING_SYSTEMS_TAG_ID)),
            "items", java.util.List.of(item));
    return applyProposal(proposalId, key, body);
  }

  protected MvcResult applyProposal(UUID proposalId, String key, Map<String, Object> body)
      throws Exception {
    return mvc.perform(
            post("/api/v1/analysis-proposals/{id}/apply", proposalId)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(json.writeValueAsBytes(body)))
        .andReturn();
  }

  protected MvcResult undoApplication(UUID applicationId, String key) throws Exception {
    return mvc.perform(
            post("/api/v1/analysis-applications/{id}/undo", applicationId)
                .header("Idempotency-Key", key))
        .andReturn();
  }

  protected JsonNode response(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray());
  }

  private static String environmentOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
