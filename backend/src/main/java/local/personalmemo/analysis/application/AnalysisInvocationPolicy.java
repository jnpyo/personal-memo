package local.personalmemo.analysis.application;

import java.util.Objects;
import local.personalmemo.analysis.domain.AnalysisRoute;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudTransferMode;

/**
 * Separates the proposal's semantic route from the configured decision to invoke a gateway.
 *
 * <p>The default policy preserves deterministic uncertainty routing. AI-preferred operation is
 * intentionally valid only for a descriptor already bound to a machine-local model gateway.
 */
public final class AnalysisInvocationPolicy {
  public static final String VERSION = "model-invocation-v1";

  private final AnalysisInvocationMode mode;

  AnalysisInvocationPolicy(AnalysisInvocationProperties properties) {
    this.mode = Objects.requireNonNull(properties, "properties").getMode();
  }

  public AnalysisInvocationDecision decide(
      AnalysisRoute semanticRoute, CloudGatewayBinding binding) {
    AnalysisRoute requiredRoute = Objects.requireNonNull(semanticRoute, "semanticRoute");
    if (mode == AnalysisInvocationMode.AI_PREFERRED) {
      requireMachineLocalBinding(binding);
      return decision(
          true,
          requiredRoute == AnalysisRoute.CLOUD_ENRICH
              ? AnalysisInvocationReason.SEMANTIC_UNCERTAINTY
              : AnalysisInvocationReason.AI_PREFERRED_POLICY);
    }
    return decision(
        requiredRoute == AnalysisRoute.CLOUD_ENRICH, AnalysisInvocationReason.SEMANTIC_UNCERTAINTY);
  }

  public AnalysisInvocationMode mode() {
    return mode;
  }

  private AnalysisInvocationDecision decision(
      boolean shouldInvoke, AnalysisInvocationReason reason) {
    return new AnalysisInvocationDecision(shouldInvoke, VERSION, mode, reason);
  }

  private void requireMachineLocalBinding(CloudGatewayBinding binding) {
    if (binding == null
        || binding.descriptor().transferMode() != CloudTransferMode.LOCAL_MACHINE_MEMO_CONTENT) {
      throw new IllegalStateException("AI_PREFERRED requires a bound machine-local model gateway.");
    }
  }
}
