package local.personalmemo.analysis.domain;

/** Raw-free, server-owned reasons for invoking the local model fallback. */
public enum FallbackReasonCode {
  DEFAULT_RECORD_FALLBACK,
  UNPARSED_TEMPORAL_CUE,
  UNRECOGNIZED_ACTION_CUE,
  LOW_TYPE_MARGIN,
  TAG_UNCERTAINTY,
  DATE_UNCERTAINTY,
  UNRESOLVED_REFERENCE,
  INCOMPLETE_TASK,
  MULTI_INTENT,
  CANDIDATE_LIMIT,
  LOCAL_CONFLICT
}
