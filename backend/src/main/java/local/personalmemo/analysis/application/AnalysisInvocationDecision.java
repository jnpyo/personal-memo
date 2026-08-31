package local.personalmemo.analysis.application;

import java.util.Objects;

/** Versioned, raw-free decision that is independent of the proposal's semantic route. */
public record AnalysisInvocationDecision(
    boolean shouldInvoke,
    String policyVersion,
    AnalysisInvocationMode mode,
    AnalysisInvocationReason reason) {

  public AnalysisInvocationDecision {
    if (!AnalysisInvocationPolicy.VERSION.equals(policyVersion)) {
      throw new IllegalArgumentException("The analysis invocation policy version is unsupported.");
    }
    mode = Objects.requireNonNull(mode, "mode");
    reason = Objects.requireNonNull(reason, "reason");
  }
}
