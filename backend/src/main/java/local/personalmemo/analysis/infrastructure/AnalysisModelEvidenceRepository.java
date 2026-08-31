package local.personalmemo.analysis.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.domain.AnalysisProposalChangedField;
import local.personalmemo.analysis.domain.FallbackReasonCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisModelEvidenceRepository {
  static final String FIND_RUN_COHORT_SQL =
      """
      select (d.state is not null) as has_dispatch,
             (r.cloud_model_version <> 'none') as has_model_version,
             (
               d.state is not null
               and r.cloud_transfer_mode = 'LOCAL_MACHINE_MEMO_CONTENT'
               and r.cloud_model_version <> 'none'
             ) as is_local_model_route,
             (d.state is not null and r.cloud_transfer_mode = 'EXTERNAL_MEMO_CONTENT')
               as is_external_memo_transfer_route,
             (
               d.state is not null
               and r.cloud_transfer_mode = 'NO_NETWORK'
               and r.cloud_gateway_version = 'fake-cloud-v2'
               and r.cloud_provider_id = 'fake'
               and r.cloud_model_version = 'none'
               and r.cloud_consent_policy_version = 'no-network-v1'
             ) as is_built_in_fake_route,
             (
               d.state is not null
               and (
                 r.cloud_transfer_mode in ('LEGACY_UNKNOWN', 'DESCRIPTOR_UNAVAILABLE')
                 or (
                   r.cloud_transfer_mode = 'NO_NETWORK'
                   and not (
                     r.cloud_gateway_version = 'fake-cloud-v2'
                     and r.cloud_provider_id = 'fake'
                     and r.cloud_model_version = 'none'
                     and r.cloud_consent_policy_version = 'no-network-v1'
                   )
                 )
               )
             ) as is_legacy_or_other_route,
             d.state as dispatch_state,
             d.local_decision_evidence_version,
             d.fallback_policy_version,
             d.invocation_policy_version,
             d.invocation_mode,
             d.invocation_reason_code,
             d.model_contribution_status,
             d.approved_correction_context_version,
             d.approved_correction_context_count,
             jsonb_array_length(d.fallback_reason_codes) as fallback_reason_count,
             jsonb_array_length(d.model_changed_fields) as changed_field_count,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'DEFAULT_RECORD_FALLBACK'), false)
               as has_default_record_fallback,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'UNPARSED_TEMPORAL_CUE'), false)
               as has_unparsed_temporal_cue,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'UNRECOGNIZED_ACTION_CUE'), false)
               as has_unrecognized_action_cue,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'LOW_TYPE_MARGIN'), false)
               as has_low_type_margin,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'TAG_UNCERTAINTY'), false)
               as has_tag_uncertainty,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'DATE_UNCERTAINTY'), false)
               as has_date_uncertainty,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'UNRESOLVED_REFERENCE'), false)
               as has_unresolved_reference,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'INCOMPLETE_TASK'), false)
               as has_incomplete_task,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'MULTI_INTENT'), false)
               as has_multi_intent,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'CANDIDATE_LIMIT'), false)
               as has_candidate_limit,
             coalesce(jsonb_exists(d.fallback_reason_codes, 'LOCAL_CONFLICT'), false)
               as has_local_conflict,
             coalesce(jsonb_exists(d.model_changed_fields, 'SUGGESTED_TITLE'), false)
               as has_suggested_title,
             coalesce(jsonb_exists(d.model_changed_fields, 'TYPE_CANDIDATES'), false)
               as has_type_candidates,
             coalesce(jsonb_exists(d.model_changed_fields, 'DATE_CANDIDATES'), false)
               as has_date_candidates,
             coalesce(jsonb_exists(d.model_changed_fields, 'TAG_CANDIDATES'), false)
               as has_tag_candidates,
             coalesce(jsonb_exists(d.model_changed_fields, 'ITEM_CANDIDATES'), false)
               as has_item_candidates,
             coalesce(jsonb_exists(d.model_changed_fields, 'RELATION_CANDIDATES'), false)
               as has_relation_candidates,
             coalesce(jsonb_exists(d.model_changed_fields, 'AMBIGUITY_REASONS'), false)
               as has_ambiguity_reasons
        from analysis_runs r
        left join analysis_run_dispatches d
          on d.analysis_run_id = r.id
         and d.owner_id = r.owner_id
       where r.owner_id = :ownerId
         and r.created_at >= :fromInclusive
         and r.created_at < :toExclusive
       limit :limit
      """;

  private final JdbcClient db;

  public AnalysisModelEvidenceRepository(JdbcClient db) {
    this.db = db;
  }

  public List<ModelEvidenceJdbcRow> findRunCohort(
      UUID ownerId, Instant fromInclusive, Instant toExclusive, int limit) {
    return db.sql(FIND_RUN_COHORT_SQL)
        .param("ownerId", ownerId)
        .param("fromInclusive", Timestamp.from(fromInclusive))
        .param("toExclusive", Timestamp.from(toExclusive))
        .param("limit", limit)
        .query(this::mapRow)
        .list();
  }

  private ModelEvidenceJdbcRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
    EnumSet<FallbackReasonCode> fallbackReasons = EnumSet.noneOf(FallbackReasonCode.class);
    addIf(
        fallbackReasons,
        FallbackReasonCode.DEFAULT_RECORD_FALLBACK,
        resultSet.getBoolean("has_default_record_fallback"));
    addIf(
        fallbackReasons,
        FallbackReasonCode.UNPARSED_TEMPORAL_CUE,
        resultSet.getBoolean("has_unparsed_temporal_cue"));
    addIf(
        fallbackReasons,
        FallbackReasonCode.UNRECOGNIZED_ACTION_CUE,
        resultSet.getBoolean("has_unrecognized_action_cue"));
    addIf(
        fallbackReasons,
        FallbackReasonCode.LOW_TYPE_MARGIN,
        resultSet.getBoolean("has_low_type_margin"));
    addIf(
        fallbackReasons,
        FallbackReasonCode.TAG_UNCERTAINTY,
        resultSet.getBoolean("has_tag_uncertainty"));
    addIf(
        fallbackReasons,
        FallbackReasonCode.DATE_UNCERTAINTY,
        resultSet.getBoolean("has_date_uncertainty"));
    addIf(
        fallbackReasons,
        FallbackReasonCode.UNRESOLVED_REFERENCE,
        resultSet.getBoolean("has_unresolved_reference"));
    addIf(
        fallbackReasons,
        FallbackReasonCode.INCOMPLETE_TASK,
        resultSet.getBoolean("has_incomplete_task"));
    addIf(
        fallbackReasons, FallbackReasonCode.MULTI_INTENT, resultSet.getBoolean("has_multi_intent"));
    addIf(
        fallbackReasons,
        FallbackReasonCode.CANDIDATE_LIMIT,
        resultSet.getBoolean("has_candidate_limit"));
    addIf(
        fallbackReasons,
        FallbackReasonCode.LOCAL_CONFLICT,
        resultSet.getBoolean("has_local_conflict"));

    EnumSet<AnalysisProposalChangedField> changedFields =
        EnumSet.noneOf(AnalysisProposalChangedField.class);
    addIf(
        changedFields,
        AnalysisProposalChangedField.SUGGESTED_TITLE,
        resultSet.getBoolean("has_suggested_title"));
    addIf(
        changedFields,
        AnalysisProposalChangedField.TYPE_CANDIDATES,
        resultSet.getBoolean("has_type_candidates"));
    addIf(
        changedFields,
        AnalysisProposalChangedField.DATE_CANDIDATES,
        resultSet.getBoolean("has_date_candidates"));
    addIf(
        changedFields,
        AnalysisProposalChangedField.TAG_CANDIDATES,
        resultSet.getBoolean("has_tag_candidates"));
    addIf(
        changedFields,
        AnalysisProposalChangedField.ITEM_CANDIDATES,
        resultSet.getBoolean("has_item_candidates"));
    addIf(
        changedFields,
        AnalysisProposalChangedField.RELATION_CANDIDATES,
        resultSet.getBoolean("has_relation_candidates"));
    addIf(
        changedFields,
        AnalysisProposalChangedField.AMBIGUITY_REASONS,
        resultSet.getBoolean("has_ambiguity_reasons"));

    return new ModelEvidenceJdbcRow(
        resultSet.getBoolean("has_dispatch"),
        resultSet.getBoolean("has_model_version"),
        resultSet.getBoolean("is_local_model_route"),
        resultSet.getBoolean("is_external_memo_transfer_route"),
        resultSet.getBoolean("is_built_in_fake_route"),
        resultSet.getBoolean("is_legacy_or_other_route"),
        resultSet.getString("dispatch_state"),
        resultSet.getString("local_decision_evidence_version"),
        resultSet.getString("fallback_policy_version"),
        resultSet.getString("invocation_policy_version"),
        resultSet.getString("invocation_mode"),
        resultSet.getString("invocation_reason_code"),
        resultSet.getString("model_contribution_status"),
        resultSet.getString("approved_correction_context_version"),
        resultSet.getObject("approved_correction_context_count", Integer.class),
        resultSet.getObject("fallback_reason_count", Integer.class),
        resultSet.getObject("changed_field_count", Integer.class),
        fallbackReasons,
        changedFields);
  }

  private static <E extends Enum<E>> void addIf(Set<E> target, E value, boolean present) {
    if (present) {
      target.add(value);
    }
  }

  public record ModelEvidenceJdbcRow(
      boolean hasDispatch,
      boolean hasModelVersion,
      boolean localModelRoute,
      boolean externalMemoTransferRoute,
      boolean builtInFakeRoute,
      boolean legacyOrOtherRoute,
      String dispatchState,
      String localDecisionEvidenceVersion,
      String fallbackPolicyVersion,
      String invocationPolicyVersion,
      String invocationMode,
      String invocationReasonCode,
      String modelContributionStatus,
      String approvedCorrectionContextVersion,
      Integer approvedCorrectionContextCount,
      Integer fallbackReasonCount,
      Integer changedFieldCount,
      Set<FallbackReasonCode> fallbackReasons,
      Set<AnalysisProposalChangedField> changedFields) {
    public ModelEvidenceJdbcRow {
      fallbackReasons = Set.copyOf(fallbackReasons);
      changedFields = Set.copyOf(changedFields);
    }
  }
}
