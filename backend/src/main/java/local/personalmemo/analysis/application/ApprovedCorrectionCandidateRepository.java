package local.personalmemo.analysis.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads a bounded, same-owner cohort without creating a second correction or raw-content store. */
@Repository
public class ApprovedCorrectionCandidateRepository {
  static final int MAX_SCAN_CANDIDATES = 64;
  static final String FIND_LATEST_CURRENT_APPLIED_SQL =
      """
      select latest_application.application_id,
             latest_application.application_status,
             latest_application.application_memo_id,
             latest_application.application_memo_revision,
             latest_application.applied_at,
             memo.id as source_memo_id,
             memo.current_revision as source_current_revision,
             memo.status as memo_status,
             current_revision.content as source_content,
             run.memo_id as run_memo_id,
             run.memo_revision as run_memo_revision,
             run.status as run_status,
             run.schema_version as run_schema_version,
             proposal.proposal_json::text as proposal_json,
             latest_application.selection_json
        from memos memo
        join memo_revisions current_revision
          on current_revision.memo_id = memo.id
         and current_revision.owner_id = memo.owner_id
         and current_revision.revision = memo.current_revision
        join lateral (
          select application.id as application_id,
                 application.status as application_status,
                 application.proposal_id,
                 application.memo_id as application_memo_id,
                 application.memo_revision as application_memo_revision,
                 application.selection_json::text as selection_json,
                 application.applied_at
            from analysis_applications application
           where application.owner_id = memo.owner_id
             and application.memo_id = memo.id
             and application.memo_revision = memo.current_revision
             and application.status = 'APPLIED'
           order by application.applied_at desc, application.id desc
           limit 1
        ) latest_application on true
        join analysis_proposals proposal
          on proposal.id = latest_application.proposal_id
         and proposal.owner_id = memo.owner_id
        join analysis_runs run
          on run.id = proposal.analysis_run_id
         and run.owner_id = proposal.owner_id
         and run.memo_id = latest_application.application_memo_id
         and run.memo_revision = latest_application.application_memo_revision
       where memo.owner_id = :ownerId
         and memo.id <> :targetMemoId
         and memo.status = 'ACTIVE'
         and run.status = 'APPLIED'
         and run.schema_version in ('1', '2', '3')
         and case
               when jsonb_typeof(proposal.proposal_json -> 'itemCandidates') = 'array'
                 then jsonb_array_length(proposal.proposal_json -> 'itemCandidates') = 1
               else false
             end
         and case
               when jsonb_typeof(proposal.proposal_json -> 'relationCandidates') = 'array'
                 then jsonb_array_length(proposal.proposal_json -> 'relationCandidates') = 0
               else false
             end
         and case
               when jsonb_typeof(latest_application.selection_json::jsonb -> 'items') = 'array'
                 then jsonb_array_length(latest_application.selection_json::jsonb -> 'items') = 1
               else false
             end
         and case
               when not jsonb_exists(
                      latest_application.selection_json::jsonb,
                      'selectedRelations'
                    )
                 then true
               when jsonb_typeof(
                      latest_application.selection_json::jsonb -> 'selectedRelations'
                    ) = 'array'
                 then jsonb_array_length(
                        latest_application.selection_json::jsonb -> 'selectedRelations'
                      ) = 0
               else false
             end
       order by latest_application.applied_at desc, latest_application.application_id desc
       limit :scanCap
      """;

  private final JdbcClient db;

  public ApprovedCorrectionCandidateRepository(JdbcClient db) {
    this.db = db;
  }

  public List<CandidateRow> findLatestCurrentApplied(UUID ownerId, UUID targetMemoId) {
    return db.sql(FIND_LATEST_CURRENT_APPLIED_SQL)
        .param("ownerId", ownerId)
        .param("targetMemoId", targetMemoId)
        .param("scanCap", MAX_SCAN_CANDIDATES)
        .query(this::mapRow)
        .list();
  }

  private CandidateRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
    return new CandidateRow(
        resultSet.getObject("application_id", UUID.class),
        resultSet.getString("application_status"),
        resultSet.getObject("application_memo_id", UUID.class),
        resultSet.getInt("application_memo_revision"),
        resultSet.getTimestamp("applied_at").toInstant(),
        resultSet.getObject("source_memo_id", UUID.class),
        resultSet.getInt("source_current_revision"),
        resultSet.getString("memo_status"),
        resultSet.getString("source_content"),
        resultSet.getObject("run_memo_id", UUID.class),
        resultSet.getInt("run_memo_revision"),
        resultSet.getString("run_status"),
        resultSet.getString("run_schema_version"),
        resultSet.getString("proposal_json"),
        resultSet.getString("selection_json"));
  }

  record CandidateRow(
      UUID applicationId,
      String applicationStatus,
      UUID applicationMemoId,
      int applicationMemoRevision,
      Instant appliedAt,
      UUID sourceMemoId,
      int sourceCurrentRevision,
      String memoStatus,
      String sourceContent,
      UUID runMemoId,
      int runMemoRevision,
      String runStatus,
      String runSchemaVersion,
      String proposalJson,
      String selectionJson) {
    @Override
    public String toString() {
      return "CandidateRow[redacted]";
    }
  }
}
