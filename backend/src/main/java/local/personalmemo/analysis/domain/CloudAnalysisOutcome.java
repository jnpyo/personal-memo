package local.personalmemo.analysis.domain;

public enum CloudAnalysisOutcome {
  NOT_REQUIRED,
  SUCCESS,
  CONSENT_REQUIRED,
  UNAVAILABLE,
  TIMEOUT,
  RETRY_EXHAUSTED,
  PROVIDER_ERROR,
  INVALID_RESPONSE,
  UNEXPECTED_FAILURE
}
