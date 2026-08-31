package local.personalmemo.analysis.application;

/** Selects when a validated local proposal should be sent to the configured analysis gateway. */
public enum AnalysisInvocationMode {
  UNCERTAINTY_ONLY,
  AI_PREFERRED
}
