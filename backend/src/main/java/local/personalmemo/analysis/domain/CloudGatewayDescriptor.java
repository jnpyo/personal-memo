package local.personalmemo.analysis.domain;

import java.util.Objects;
import java.util.Set;

/** Server-owned identity and data-transfer policy for one cloud enrichment adapter. */
public record CloudGatewayDescriptor(
    String gatewayVersion,
    String providerId,
    String modelVersion,
    String consentPolicyVersion,
    CloudTransferMode transferMode) {
  private static final Set<String> RESERVED_DESCRIPTOR_VALUES =
      Set.of("none", "legacy-unknown", "unavailable");

  public CloudGatewayDescriptor {
    gatewayVersion = requireVersion(gatewayVersion, "gatewayVersion");
    providerId = requireVersion(providerId, "providerId");
    modelVersion = requireVersion(modelVersion, "modelVersion");
    consentPolicyVersion = requireVersion(consentPolicyVersion, "consentPolicyVersion");
    transferMode = Objects.requireNonNull(transferMode, "transferMode");
    rejectReserved(gatewayVersion, "gatewayVersion", false);
    rejectReserved(providerId, "providerId", false);
    rejectReserved(modelVersion, "modelVersion", true);
    rejectReserved(consentPolicyVersion, "consentPolicyVersion", false);
  }

  private static String requireVersion(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > AnalysisProvenance.MAX_VERSION_LENGTH) {
      throw new IllegalArgumentException(field + " must contain 1 to 64 characters.");
    }
    return value;
  }

  private static void rejectReserved(String value, String field, boolean allowNone) {
    if (RESERVED_DESCRIPTOR_VALUES.contains(value) && !(allowNone && "none".equals(value))) {
      throw new IllegalArgumentException(field + " uses a reserved server evidence value.");
    }
  }
}
