package local.personalmemo.analysis.domain;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/** Provider-independent, defensive input for an optional cloud enrichment adapter. */
public record CloudAnalysisRequest(
    ObjectNode validatedLocalProposal,
    List<AmbiguityReason> routingReasons,
    String routingPolicyVersion) {

  public CloudAnalysisRequest {
    Objects.requireNonNull(validatedLocalProposal, "validatedLocalProposal");
    validatedLocalProposal = validatedLocalProposal.deepCopy();
    routingReasons = List.copyOf(Objects.requireNonNull(routingReasons, "routingReasons"));
    if (routingPolicyVersion == null
        || routingPolicyVersion.isBlank()
        || routingPolicyVersion.codePointCount(0, routingPolicyVersion.length())
            > AnalysisProvenance.MAX_VERSION_LENGTH) {
      throw new IllegalArgumentException("routingPolicyVersion must contain 1 to 64 characters.");
    }
  }

  @Override
  public ObjectNode validatedLocalProposal() {
    return validatedLocalProposal.deepCopy();
  }
}
