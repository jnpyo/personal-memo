package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AnalysisModelEvidenceRepositoryContractTest {

  @Test
  void queryIsOwnerBoundedAndSelectsOnlyScalarOrContainmentEvidence() {
    String sql = AnalysisModelEvidenceRepository.FIND_RUN_COHORT_SQL.replaceAll("\\s+", " ").trim();

    assertThat(sql)
        .contains(
            "from analysis_runs r left join analysis_run_dispatches d",
            "d.analysis_run_id = r.id",
            "d.owner_id = r.owner_id",
            "r.owner_id = :ownerId",
            "r.created_at >= :fromInclusive",
            "r.created_at < :toExclusive",
            "limit :limit",
            "r.cloud_model_version <> 'none') as has_model_version",
            "r.cloud_transfer_mode = 'LOCAL_MACHINE_MEMO_CONTENT'",
            ") as is_local_model_route",
            "r.cloud_transfer_mode = 'EXTERNAL_MEMO_CONTENT') as is_external_memo_transfer_route",
            "r.cloud_gateway_version = 'fake-cloud-v2'",
            "r.cloud_provider_id = 'fake'",
            "r.cloud_consent_policy_version = 'no-network-v1'",
            ") as is_built_in_fake_route",
            ") as is_legacy_or_other_route",
            "jsonb_array_length(d.fallback_reason_codes) as fallback_reason_count",
            "jsonb_array_length(d.model_changed_fields) as changed_field_count",
            "jsonb_exists(d.fallback_reason_codes, 'DEFAULT_RECORD_FALLBACK')",
            "jsonb_exists(d.model_changed_fields, 'SUGGESTED_TITLE')")
        .doesNotContain(
            "memo_revisions",
            "analysis_proposals",
            "analysis_applications",
            "proposal_json",
            "selection_json",
            "validated_local_proposal",
            "retrieval_context",
            "d.local_decision_evidence,",
            "d.approved_correction_context,",
            "approved_correction_context_hash",
            "request_hash",
            "provider_request_token",
            "order by",
            "select (d.state is not null) as has_dispatch, r.cloud_transfer_mode,",
            "r.cloud_gateway_version as",
            "r.cloud_provider_id as",
            "r.cloud_model_version as",
            "r.cloud_consent_policy_version as",
            "r.memo_id",
            "select r.id",
            "d.analysis_run_id as");

    assertThat(
            Arrays.stream(
                    AnalysisModelEvidenceRepository.ModelEvidenceJdbcRow.class
                        .getRecordComponents())
                .map(component -> component.getName()))
        .containsExactly(
            "hasDispatch",
            "hasModelVersion",
            "localModelRoute",
            "externalMemoTransferRoute",
            "builtInFakeRoute",
            "legacyOrOtherRoute",
            "dispatchState",
            "localDecisionEvidenceVersion",
            "fallbackPolicyVersion",
            "invocationPolicyVersion",
            "invocationMode",
            "invocationReasonCode",
            "modelContributionStatus",
            "approvedCorrectionContextVersion",
            "approvedCorrectionContextCount",
            "fallbackReasonCount",
            "changedFieldCount",
            "fallbackReasons",
            "changedFields");
  }
}
