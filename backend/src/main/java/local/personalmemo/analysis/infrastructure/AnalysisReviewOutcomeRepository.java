package local.personalmemo.analysis.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisReviewOutcomeRepository {
  private final JdbcClient db;

  public AnalysisReviewOutcomeRepository(JdbcClient db) {
    this.db = db;
  }

  /** Returns at most {@code limit} proposal-cohort rows, each with only its latest application. */
  public List<ReviewOutcomeJdbcRow> findProposalCohort(
      UUID ownerId, Instant fromInclusive, Instant toExclusive, int limit) {
    return db.sql(
            """
            select r.route,
                   r.status as run_status,
                   r.schema_version as run_schema_version,
                   r.memo_id as run_memo_id,
                   r.memo_revision as run_memo_revision,
                   r.analyzer_version,
                   r.prompt_version,
                   r.local_model_version,
                   r.embedding_model_version,
                   r.routing_policy_version,
                   p.proposal_json::text as proposal_json,
                   latest_application.status as application_status,
                   latest_application.memo_id as application_memo_id,
                   latest_application.memo_revision as application_memo_revision,
                   latest_application.selection_json::text as selection_json
              from analysis_proposals p
              join analysis_runs r
                on r.id = p.analysis_run_id
               and r.owner_id = p.owner_id
              left join lateral (
                select a.status,
                       a.memo_id,
                       a.memo_revision,
                       a.selection_json
                  from analysis_applications a
                 where a.owner_id = p.owner_id
                   and a.proposal_id = p.id
                 order by a.applied_at desc, a.id desc
                 limit 1
              ) latest_application on true
             where p.owner_id = :ownerId
               and p.created_at >= :fromInclusive
               and p.created_at < :toExclusive
             order by p.created_at desc, p.id desc
             limit :limit
            """)
        .param("ownerId", ownerId)
        .param("fromInclusive", Timestamp.from(fromInclusive))
        .param("toExclusive", Timestamp.from(toExclusive))
        .param("limit", limit)
        .query(this::mapRow)
        .list();
  }

  private ReviewOutcomeJdbcRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
    return new ReviewOutcomeJdbcRow(
        resultSet.getString("route"),
        resultSet.getString("run_status"),
        resultSet.getString("run_schema_version"),
        resultSet.getObject("run_memo_id", UUID.class),
        resultSet.getInt("run_memo_revision"),
        resultSet.getString("analyzer_version"),
        resultSet.getString("prompt_version"),
        resultSet.getString("local_model_version"),
        resultSet.getString("embedding_model_version"),
        resultSet.getString("routing_policy_version"),
        resultSet.getString("proposal_json"),
        resultSet.getString("application_status"),
        resultSet.getObject("application_memo_id", UUID.class),
        resultSet.getInt("application_memo_revision"),
        resultSet.getString("selection_json"));
  }

  public record ReviewOutcomeJdbcRow(
      String route,
      String runStatus,
      String runSchemaVersion,
      UUID runMemoId,
      int runMemoRevision,
      String analyzerVersion,
      String promptVersion,
      String localModelVersion,
      String embeddingModelVersion,
      String routingPolicyVersion,
      String proposalJson,
      String applicationStatus,
      UUID applicationMemoId,
      int applicationMemoRevision,
      String selectionJson) {}
}
