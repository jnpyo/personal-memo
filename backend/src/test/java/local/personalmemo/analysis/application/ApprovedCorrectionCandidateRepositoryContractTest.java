package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApprovedCorrectionCandidateRepositoryContractTest {

  @Test
  void queryIsOwnerCurrentRevisionLatestAppliedAndBounded() {
    String sql =
        ApprovedCorrectionCandidateRepository.FIND_LATEST_CURRENT_APPLIED_SQL
            .replaceAll("\\s+", " ")
            .trim();

    assertThat(ApprovedCorrectionCandidateRepository.MAX_SCAN_CANDIDATES).isEqualTo(64);
    assertThat(sql)
        .contains(
            "current_revision.owner_id = memo.owner_id",
            "current_revision.revision = memo.current_revision",
            "application.owner_id = memo.owner_id",
            "application.memo_id = memo.id",
            "application.memo_revision = memo.current_revision",
            "application.status = 'APPLIED'",
            "order by application.applied_at desc, application.id desc limit 1",
            "proposal.owner_id = memo.owner_id",
            "run.owner_id = proposal.owner_id",
            "run.memo_id = latest_application.application_memo_id",
            "run.memo_revision = latest_application.application_memo_revision",
            "memo.owner_id = :ownerId",
            "memo.id <> :targetMemoId",
            "memo.status = 'ACTIVE'",
            "run.status = 'APPLIED'",
            "run.schema_version in ('1', '2', '3')",
            "jsonb_array_length(proposal.proposal_json -> 'itemCandidates') = 1",
            "jsonb_array_length(proposal.proposal_json -> 'relationCandidates') = 0",
            "jsonb_array_length(latest_application.selection_json::jsonb -> 'items') = 1",
            "jsonb_exists( latest_application.selection_json::jsonb, 'selectedRelations' )",
            "latest_application.selection_json::jsonb -> 'selectedRelations'",
            "order by latest_application.applied_at desc, latest_application.application_id desc",
            "limit :scanCap");
  }
}
