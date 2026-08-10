package local.personalmemo.analysis.domain;

public enum CloudAnalysisFailureReason {
  UNAVAILABLE,
  TIMEOUT,
  RETRY_EXHAUSTED,
  PROVIDER_ERROR
}
