package local.personalmemo.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Opt-in, bounded Korean 10k-corpus EXPLAIN runner. Its name intentionally does not match
 * Surefire's default test patterns.
 */
class MemoSearchQueryPlanBenchmarkRunner extends PostgresIntegrationTestSupport {
  private static final int CORPUS_SIZE = 10_000;
  private static final int FETCH_SIZE = 21;
  private static final Instant SNAPSHOT = Instant.parse("2026-08-11T09:00:00Z");
  private static final UUID ZERO_UUID = new UUID(0, 0);
  private static final Path REPORT = Path.of("target", "memo-search-query-plan-report.json");

  @BeforeAll
  static void removeStaleReportAndRequireExplicitOptIn() throws Exception {
    Files.deleteIfExists(REPORT);
    if (!"true".equals(System.getenv("RUN_MEMO_SEARCH_PLAN_BENCHMARK"))) {
      throw new IllegalStateException(
          "RUN_MEMO_SEARCH_PLAN_BENCHMARK=true is required for this explicit runner.");
    }
  }

  @Test
  void writeBoundedExplainReportForTenThousandKoreanCanonicalMemos() throws Exception {
    seedCorpus();

    PlanEvidence bodyPage = explainPage("검색대상", "검색대상");
    PlanEvidence bodyDigest = explainDigest("검색대상", "검색대상");
    PlanEvidence aliasPage = explainPage("성능별칭", "성능별칭");
    PlanEvidence aliasDigest = explainDigest("성능별칭", "성능별칭");

    assertThat(bodyPage.actualRows()).isEqualTo(FETCH_SIZE);
    assertThat(aliasPage.actualRows()).isEqualTo(FETCH_SIZE);
    assertThat(bodyDigest.actualRows()).isEqualTo(1);
    assertThat(aliasDigest.actualRows()).isEqualTo(1);

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("formatVersion", "1");
    Map<String, Object> environment = new LinkedHashMap<>();
    environment.put("databaseImage", "postgres:17.6-alpine");
    environment.put("databaseVersion", db.sql("show server_version").query(String.class).single());
    environment.put(
        "databaseLocale",
        db.sql(
                "select datcollate || '/' || datctype from pg_database "
                    + "where datname=current_database()")
            .query(String.class)
            .single());
    environment.put(
        "icuRootCollation",
        db.sql("select count(*)=1 from pg_collation where collname='und-x-icu'")
            .query(Boolean.class)
            .single());
    environment.put("runnerImage", "maven:3.9.11-eclipse-temurin-21");
    environment.put("javaFeatureVersion", Runtime.version().feature());
    report.put("environment", environment);
    Map<String, Object> corpus = new LinkedHashMap<>();
    corpus.put("ownerCount", 1);
    corpus.put("memoCount", CORPUS_SIZE);
    corpus.put("currentRevisionCount", CORPUS_SIZE);
    corpus.put("appliedTitleCount", CORPUS_SIZE);
    corpus.put("activeCanonicalTagLinkCount", CORPUS_SIZE);
    corpus.put("canonicalTaskCount", CORPUS_SIZE);
    corpus.put("pageQueryFetchSize", FETCH_SIZE);
    corpus.put("snapshotAsOf", SNAPSHOT.toString());
    report.put("corpus", corpus);
    Map<String, Object> plans = new LinkedHashMap<>();
    plans.put("koreanBodyAndTitlePage", bodyPage.asMap());
    plans.put("koreanBodyAndTitleDigest", bodyDigest.asMap());
    plans.put("exactKoreanAliasPage", aliasPage.asMap());
    plans.put("exactKoreanAliasDigest", aliasDigest.asMap());
    report.put("plans", plans);

    publishReport(report);
    System.out.println("Memo search query-plan report: " + REPORT.toAbsolutePath());
  }

  private PlanEvidence explainPage(String textQuery, String tagQuery) throws Exception {
    String query = MemoSearchRepository.pageSql();
    String raw =
        bind(db.sql(explain(query)), textQuery, tagQuery)
            .param("hasCursor", false)
            .param("cursorRevisedAt", Timestamp.from(Instant.EPOCH))
            .param("cursorMemoId", ZERO_UUID)
            .param("limit", FETCH_SIZE)
            .query((resultSet, rowNumber) -> resultSet.getString(1))
            .single();
    return evidence(query, raw);
  }

  private PlanEvidence explainDigest(String textQuery, String tagQuery) throws Exception {
    String query = MemoSearchRepository.digestSql();
    String raw =
        bind(db.sql(explain(query)), textQuery, tagQuery)
            .param("digestShape", "MEMO_LEXICAL_SEARCH_SNAPSHOT_V1")
            .param("sortShape", "CURRENT_REVISION_RECENCY_V1")
            .param("queryDigest", sha256(textQuery))
            .param("filterDigest", sha256("benchmark-active-defaults"))
            .param("snapshotAsOfText", SNAPSHOT.toString())
            .query((resultSet, rowNumber) -> resultSet.getString(1))
            .single();
    return evidence(query, raw);
  }

  private JdbcClient.StatementSpec bind(
      JdbcClient.StatementSpec statement, String textQuery, String tagQuery) {
    return statement
        .param("ownerId", OWNER_ID)
        .param("textQuery", textQuery)
        .param("tagQueryEligible", true)
        .param("tagQuery", tagQuery)
        .param("lifecycleStatus", "ACTIVE")
        .param("snapshotAsOf", Timestamp.from(SNAPSHOT))
        .param("hasTaskState", false)
        .param("taskState", "NONE")
        .param("hasOverdue", false)
        .param("overdue", false)
        .param("hasRevisedFrom", false)
        .param("revisedFrom", Timestamp.from(Instant.EPOCH))
        .param("hasRevisedBefore", false)
        .param("revisedBefore", Timestamp.from(SNAPSHOT));
  }

  private void seedCorpus() {
    db.sql("truncate table memos, tags cascade").update();
    UUID tagId = UUID.fromString("88000000-0000-0000-0000-000000000001");
    db.sql(
            """
            insert into tags(
              id, owner_id, canonical_name, normalized_name, state,
              created_at, updated_at, version, created_by_application_id
            ) values(
              :tag, :owner, '성능 태그', '성능 태그', 'ACTIVE',
              '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', 0, null
            )
            """)
        .param("tag", tagId)
        .param("owner", OWNER_ID)
        .update();
    db.sql(
            """
            insert into tag_aliases(
              id, owner_id, tag_id, alias, normalized_alias, source, created_at
            ) values(
              '88000000-0000-0000-0000-000000000002', :owner, :tag,
              '성능별칭', '성능별칭', 'USER', '2026-01-01T00:00:00Z'
            )
            """)
        .param("owner", OWNER_ID)
        .param("tag", tagId)
        .update();
    db.sql(
            """
            insert into memos(
              id, owner_id, current_revision, status, pinned,
              created_at, updated_at, deleted_at, version
            )
            select md5('search-perf-memo-' || g)::uuid,
                   :owner,
                   1,
                   'ACTIVE',
                   g % 100 = 0,
                   '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => g),
                   '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => g),
                   null,
                   0
              from generate_series(1, :corpusSize) g
            """)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql(
            """
            insert into memo_revisions(
              memo_id, revision, content, content_hash, created_at, created_by,
              owner_id, client_recorded_at, source_time_zone
            )
            select md5('search-perf-memo-' || g)::uuid,
                   1,
                   '검색대상 한국어 성능 메모 ' || lpad(g::text, 5, '0'),
                   encode(sha256(convert_to('search-perf-' || g, 'UTF8')), 'hex'),
                   '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => g),
                   :owner,
                   :owner,
                   '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => g),
                   'Asia/Seoul'
              from generate_series(1, :corpusSize) g
            """)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql(
            """
            insert into analysis_runs(
              id, owner_id, memo_id, memo_revision, route, status, schema_version,
              analyzer_version, created_at, completed_at, cloud_execution_contract_version
            )
            select md5('search-perf-run-' || g)::uuid,
                   :owner,
                   md5('search-perf-memo-' || g)::uuid,
                   1,
                   'MOCK',
                   'APPLIED',
                   '1',
                   'synthetic-search-v1',
                   '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => g),
                   '2026-01-01T00:00:01Z'::timestamptz + make_interval(secs => g),
                   'legacy-v0'
              from generate_series(1, :corpusSize) g
            """)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql(
            """
            insert into analysis_proposals(
              id, analysis_run_id, proposal_json, proposal_hash, created_at, owner_id
            )
            select md5('search-perf-proposal-' || g)::uuid,
                   md5('search-perf-run-' || g)::uuid,
                   '{}'::jsonb,
                   repeat('b', 64),
                   '2026-01-01T00:00:00Z'::timestamptz + make_interval(secs => g),
                   :owner
              from generate_series(1, :corpusSize) g
            """)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql(
            """
            insert into analysis_applications(
              id, owner_id, proposal_id, memo_id, memo_revision, idempotency_key,
              status, selection_json, applied_at, undone_at
            )
            select md5('search-perf-app-' || g)::uuid,
                   :owner,
                   md5('search-perf-proposal-' || g)::uuid,
                   md5('search-perf-memo-' || g)::uuid,
                   1,
                   'search-perf-' || g,
                   'APPLIED',
                   jsonb_build_object(
                     'title', '승인 검색대상 제목 ' || lpad(g::text, 5, '0'),
                     'selectedType', 'TASK'
                   ),
                   '2026-01-01T00:00:01Z'::timestamptz + make_interval(secs => g),
                   null
              from generate_series(1, :corpusSize) g
            """)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql(
            """
            insert into memo_items(
              id, owner_id, memo_id, memo_revision, application_id,
              kind, title, created_at, archived_at
            )
            select md5('search-perf-item-' || g)::uuid,
                   :owner,
                   md5('search-perf-memo-' || g)::uuid,
                   1,
                   md5('search-perf-app-' || g)::uuid,
                   'TASK',
                   '성능 작업 ' || lpad(g::text, 5, '0'),
                   '2026-01-01T00:00:01Z'::timestamptz + make_interval(secs => g),
                   null
              from generate_series(1, :corpusSize) g
            """)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql(
            """
            insert into task_details(
              memo_item_id, status, due_at_utc, date_surface_text, date_precision,
              source_time_zone, time_was_explicit, completed_at, due_local_date, owner_id
            )
            select md5('search-perf-item-' || g)::uuid,
                   case when g % 5 = 0 then 'DONE' else 'TODO' end,
                   '2026-08-10T00:00:00Z'::timestamptz + make_interval(days => g % 30),
                   null,
                   null,
                   'Asia/Seoul',
                   true,
                   case
                     when g % 5 = 0 then '2026-08-11T00:00:00Z'::timestamptz
                     else null
                   end,
                   null,
                   :owner
              from generate_series(1, :corpusSize) g
            """)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql(
            """
            insert into item_tags(
              memo_item_id, tag_id, application_id, source, score, confirmed_at, owner_id
            )
            select md5('search-perf-item-' || g)::uuid,
                   :tag,
                   md5('search-perf-app-' || g)::uuid,
                   'USER',
                   null,
                   '2026-01-01T00:00:02Z'::timestamptz + make_interval(secs => g),
                   :owner
              from generate_series(1, :corpusSize) g
            """)
        .param("tag", tagId)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql("analyze").update();

    assertThat(db.sql("select count(*) from memos").query(Long.class).single())
        .isEqualTo((long) CORPUS_SIZE);
    assertThat(db.sql("select count(*) from memo_revisions").query(Long.class).single())
        .isEqualTo((long) CORPUS_SIZE);
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isEqualTo((long) CORPUS_SIZE);
    assertThat(db.sql("select count(*) from item_tags").query(Long.class).single())
        .isEqualTo((long) CORPUS_SIZE);
  }

  private void publishReport(Map<String, Object> report) throws Exception {
    Files.createDirectories(REPORT.getParent());
    Path pending =
        Files.createTempFile(REPORT.getParent(), REPORT.getFileName().toString() + ".", ".tmp");
    try {
      Files.writeString(
          pending,
          json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      assertThat(Files.size(pending)).isLessThan(512L * 1024L);
      Files.move(
          pending, REPORT, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(pending);
    }
  }

  private PlanEvidence evidence(String query, String raw) throws Exception {
    JsonNode explain = json.readTree(raw);
    JsonNode statement = explain.get(0);
    JsonNode root = statement.path("Plan");
    Set<String> indexes = new TreeSet<>();
    Set<String> nodeTypes = new TreeSet<>();
    collectPlanShape(root, indexes, nodeTypes);
    return new PlanEvidence(
        sha256(query),
        root.path("Node Type").asText(),
        root.path("Actual Rows").asLong(),
        statement.path("Planning Time").asDouble(),
        statement.path("Execution Time").asDouble(),
        root.path("Shared Hit Blocks").asLong(),
        root.path("Shared Read Blocks").asLong(),
        root.path("Temp Read Blocks").asLong(),
        root.path("Temp Written Blocks").asLong(),
        List.copyOf(indexes),
        List.copyOf(nodeTypes),
        explain);
  }

  private void collectPlanShape(JsonNode node, Set<String> indexes, Set<String> nodeTypes) {
    String nodeType = node.path("Node Type").asText();
    if (!nodeType.isBlank()) {
      nodeTypes.add(nodeType);
    }
    String index = node.path("Index Name").asText();
    if (!index.isBlank()) {
      indexes.add(index);
    }
    node.path("Plans").forEach(child -> collectPlanShape(child, indexes, nodeTypes));
  }

  private String explain(String query) {
    return "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query;
  }

  private String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private record PlanEvidence(
      String querySha256,
      String rootNode,
      long actualRows,
      double planningTimeMs,
      double executionTimeMs,
      long sharedHitBlocks,
      long sharedReadBlocks,
      long tempReadBlocks,
      long tempWrittenBlocks,
      List<String> indexes,
      List<String> nodeTypes,
      JsonNode explain) {
    private Map<String, Object> asMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("querySha256", querySha256);
      values.put("rootNode", rootNode);
      values.put("actualRows", actualRows);
      values.put("planningTimeMs", planningTimeMs);
      values.put("executionTimeMs", executionTimeMs);
      values.put("sharedHitBlocks", sharedHitBlocks);
      values.put("sharedReadBlocks", sharedReadBlocks);
      values.put("tempReadBlocks", tempReadBlocks);
      values.put("tempWrittenBlocks", tempWrittenBlocks);
      values.put("indexes", new ArrayList<>(indexes));
      values.put("nodeTypes", new ArrayList<>(nodeTypes));
      values.put("explain", explain);
      return values;
    }
  }
}
