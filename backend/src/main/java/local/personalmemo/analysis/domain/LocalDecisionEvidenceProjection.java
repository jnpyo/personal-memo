package local.personalmemo.analysis.domain;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/** A raw-free evidence document and the strict fallback reasons derived from the same proposal. */
public record LocalDecisionEvidenceProjection(
    ObjectNode evidence, List<FallbackReasonCode> fallbackReasonCodes) {
  public static final String EVIDENCE_VERSION = "local-decision-v1";
  public static final String FALLBACK_POLICY_VERSION = "model-fallback-v1";

  public LocalDecisionEvidenceProjection {
    evidence = Objects.requireNonNull(evidence, "evidence").deepCopy();
    fallbackReasonCodes =
        List.copyOf(Objects.requireNonNull(fallbackReasonCodes, "fallbackReasonCodes"));
  }

  @Override
  public ObjectNode evidence() {
    return evidence.deepCopy();
  }
}
