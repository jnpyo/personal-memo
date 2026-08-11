package local.personalmemo.graph.application;

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
import tools.jackson.databind.JsonNode;

/**
 * Opt-in, bounded 10k-corpus EXPLAIN runner. Its name intentionally does not match Surefire's
 * default test patterns.
 *
 * <p>From the repository root, PostgreSQL 17.6 and Java 21/Maven 3.9.11 are supplied by {@code
 * compose.test.yaml}:
 *
 * <pre>
 * docker compose -p personal-memo-neighborhood-plan -f compose.test.yaml run --rm -e RUN_GRAPH_NEIGHBORHOOD_PLAN_BENCHMARK=true backend-integration mvn -B -Dtest=GraphNeighborhoodQueryPlanBenchmarkRunner test
 * docker compose -p personal-memo-neighborhood-plan -f compose.test.yaml run --rm --no-deps backend-integration sh -c 'cat target/graph-neighborhood-query-plan-report.json'
 * docker compose -p personal-memo-neighborhood-plan -f compose.test.yaml down --volumes --remove-orphans
 * </pre>
 */
class GraphNeighborhoodQueryPlanBenchmarkRunner extends PostgresIntegrationTestSupport {
  private static final int CORPUS_SIZE = 10_000;
  private static final int FETCH_SIZE = 21;
  private static final Instant SNAPSHOT = Instant.parse("2026-08-11T09:00:00Z");
  private static final UUID ZERO_UUID = new UUID(0, 0);
  private static final Path REPORT = Path.of("target", "graph-neighborhood-query-plan-report.json");

  @BeforeAll
  static void removeStaleReportAndRequireExplicitOptIn() throws Exception {
    Files.deleteIfExists(REPORT);
    if (!"true".equals(System.getenv("RUN_GRAPH_NEIGHBORHOOD_PLAN_BENCHMARK"))) {
      throw new IllegalStateException(
          "RUN_GRAPH_NEIGHBORHOOD_PLAN_BENCHMARK=true is required for this explicit runner.");
    }
  }

  @Test
  void writeBoundedExplainReportForTenThousandOwnerScopedNodes() throws Exception {
    seedCorpus();
    UUID memoCenter = scalarUuid("select md5('perf-memo-1')::uuid");
    UUID tagCenter = scalarUuid("select md5('perf-tag-1')::uuid");

    PlanEvidence memoToTagPage = explainMemoToTagPage(memoCenter);
    PlanEvidence memoToTagDigest = explainMemoToTagDigest(memoCenter);
    PlanEvidence tagToMemoPage = explainTagToMemoPage(tagCenter);
    PlanEvidence tagToMemoDigest = explainTagToMemoDigest(tagCenter);

    assertThat(memoToTagPage.actualRows()).isEqualTo(FETCH_SIZE);
    assertThat(tagToMemoPage.actualRows()).isEqualTo(FETCH_SIZE);
    assertThat(memoToTagDigest.actualRows()).isEqualTo(1);
    assertThat(tagToMemoDigest.actualRows()).isEqualTo(1);
    assertThat(memoToTagPage.indexes())
        .contains("idx_memo_items_owner_memo_active_application", "item_tags_pkey");
    assertThat(tagToMemoPage.indexes())
        .contains(
            "idx_analysis_applications_owner_memo_applied_latest",
            "idx_memo_items_owner_memo_active_application");

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("formatVersion", "1");
    Map<String, Object> environment = new LinkedHashMap<>();
    environment.put("databaseImage", "postgres:17.6-alpine");
    environment.put("databaseVersion", db.sql("show server_version").query(String.class).single());
    environment.put("runnerImage", "maven:3.9.11-eclipse-temurin-21");
    environment.put("javaFeatureVersion", Runtime.version().feature());
    report.put("environment", environment);
    Map<String, Object> corpus = new LinkedHashMap<>();
    corpus.put("ownerCount", 1);
    corpus.put("memoCount", CORPUS_SIZE);
    corpus.put("tagCount", CORPUS_SIZE);
    corpus.put("canonicalLinkCount", 19_999);
    corpus.put("pageQueryFetchSize", FETCH_SIZE);
    corpus.put("snapshotAsOf", SNAPSHOT.toString());
    report.put("corpus", corpus);
    Map<String, Object> plans = new LinkedHashMap<>();
    plans.put("memoToTagPage", memoToTagPage.asMap());
    plans.put("memoToTagDigest", memoToTagDigest.asMap());
    plans.put("tagToMemoPage", tagToMemoPage.asMap());
    plans.put("tagToMemoDigest", tagToMemoDigest.asMap());
    report.put("plans", plans);

    publishReport(report);
    System.out.println("Graph neighborhood query-plan report: " + REPORT.toAbsolutePath());
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

  private void seedCorpus() {
    db.sql("truncate table memos, tags cascade").update();
    db.sql(
            """
            insert into tags(
              id, owner_id, canonical_name, normalized_name, state,
              created_at, updated_at, version, created_by_application_id
            )
            select md5('perf-tag-' || g)::uuid,
                   :owner,
                   'Perf Tag ' || lpad(g::text, 5, '0'),
                   'perf-tag-' || lpad(g::text, 5, '0'),
                   'ACTIVE',
                   '2026-08-01T00:00:00Z'::timestamptz,
                   '2026-08-01T00:00:00Z'::timestamptz,
                   0,
                   null
              from generate_series(1, :corpusSize) g
            """)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql(
            """
            insert into memos(
              id, owner_id, current_revision, status, pinned,
              created_at, updated_at, deleted_at, version
            )
            select md5('perf-memo-' || g)::uuid,
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
            select md5('perf-memo-' || g)::uuid,
                   1,
                   'synthetic performance memo',
                   repeat('a', 64),
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
            select md5('perf-run-' || g)::uuid,
                   :owner,
                   md5('perf-memo-' || g)::uuid,
                   1,
                   'MOCK',
                   'APPLIED',
                   '1',
                   'synthetic-v1',
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
            select md5('perf-proposal-' || g)::uuid,
                   md5('perf-run-' || g)::uuid,
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
            select md5('perf-app-' || g)::uuid,
                   :owner,
                   md5('perf-proposal-' || g)::uuid,
                   md5('perf-memo-' || g)::uuid,
                   1,
                   'perf-' || g,
                   'APPLIED',
                   jsonb_build_object('title', 'Perf memo ' || g, 'selectedType', 'TASK'),
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
            select md5('perf-item-' || g)::uuid,
                   :owner,
                   md5('perf-memo-' || g)::uuid,
                   1,
                   md5('perf-app-' || g)::uuid,
                   'TASK',
                   'Perf memo ' || g,
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
            select md5('perf-item-' || g)::uuid,
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
            select md5('perf-item-' || g)::uuid,
                   md5('perf-tag-1')::uuid,
                   md5('perf-app-' || g)::uuid,
                   'USER',
                   null,
                   '2026-01-01T00:00:02Z'::timestamptz + make_interval(secs => g),
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
            select md5('perf-item-1')::uuid,
                   md5('perf-tag-' || g)::uuid,
                   md5('perf-app-1')::uuid,
                   'USER',
                   null,
                   '2026-01-01T00:00:02Z'::timestamptz + make_interval(secs => g),
                   :owner
              from generate_series(1, :corpusSize) g
            on conflict do nothing
            """)
        .param("owner", OWNER_ID)
        .param("corpusSize", CORPUS_SIZE)
        .update();
    db.sql("analyze").update();

    assertThat(db.sql("select count(*) from memos").query(Long.class).single())
        .isEqualTo((long) CORPUS_SIZE);
    assertThat(db.sql("select count(*) from tags").query(Long.class).single())
        .isEqualTo((long) CORPUS_SIZE);
    assertThat(db.sql("select count(*) from item_tags").query(Long.class).single())
        .isEqualTo(19_999L);
  }

  private PlanEvidence explainMemoToTagPage(UUID centerId) throws Exception {
    String query = GraphNeighborhoodService.tagNeighborSql(false);
    String raw =
        db.sql(explain(query))
            .param("ownerId", OWNER_ID)
            .param("centerId", centerId)
            .param("hasCursor", false)
            .param("cursorNormalizedName", "")
            .param("cursorNeighborId", ZERO_UUID)
            .param("limit", FETCH_SIZE)
            .query((resultSet, rowNumber) -> resultSet.getString(1))
            .single();
    return evidence(query, raw);
  }

  private PlanEvidence explainMemoToTagDigest(UUID centerId) throws Exception {
    String query = GraphNeighborhoodService.memoNeighborhoodDigestSql();
    String raw =
        db.sql(explain(query))
            .param("digestShape", GraphNeighborhoodService.DIGEST_SHAPE)
            .param("ownerId", OWNER_ID)
            .param("centerId", centerId)
            .param("sortShape", GraphNeighborhoodService.TAG_SORT_SHAPE)
            .param("snapshotAsOfText", SNAPSHOT.toString())
            .param("centerLabel", "Perf memo 1")
            .param("centerNodeKind", "TASK")
            .param("centerTaskStatus", "TODO")
            .param("centerOverdue", true)
            .param("centerPinned", false)
            .query((resultSet, rowNumber) -> resultSet.getString(1))
            .single();
    return evidence(query, raw);
  }

  private PlanEvidence explainTagToMemoPage(UUID centerId) throws Exception {
    String query = GraphNeighborhoodService.memoNeighborSql(false);
    String raw =
        db.sql(explain(query))
            .param("ownerId", OWNER_ID)
            .param("centerId", centerId)
            .param("snapshotAsOf", Timestamp.from(SNAPSHOT))
            .param("hasCursor", false)
            .param("cursorPinnedRank", 0)
            .param("cursorOverdueRank", 0)
            .param("cursorTodoRank", 0)
            .param("cursorDueNullRank", 0)
            .param("cursorNextDue", Timestamp.from(Instant.EPOCH))
            .param("cursorRevisionCreatedAt", Timestamp.from(Instant.EPOCH))
            .param("cursorNeighborId", ZERO_UUID)
            .param("limit", FETCH_SIZE)
            .query((resultSet, rowNumber) -> resultSet.getString(1))
            .single();
    return evidence(query, raw);
  }

  private PlanEvidence explainTagToMemoDigest(UUID centerId) throws Exception {
    String query = GraphNeighborhoodService.tagNeighborhoodDigestSql();
    String raw =
        db.sql(explain(query))
            .param("ownerId", OWNER_ID)
            .param("centerId", centerId)
            .param("snapshotAsOf", Timestamp.from(SNAPSHOT))
            .param("digestShape", GraphNeighborhoodService.DIGEST_SHAPE)
            .param("sortShape", GraphNeighborhoodService.MEMO_SORT_SHAPE)
            .param("snapshotAsOfText", SNAPSHOT.toString())
            .param("centerLabel", "Perf Tag 00001")
            .param("centerNormalizedName", "perf-tag-00001")
            .query((resultSet, rowNumber) -> resultSet.getString(1))
            .single();
    return evidence(query, raw);
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

  private UUID scalarUuid(String sql) {
    return db.sql(sql).query(UUID.class).single();
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
