package local.personalmemo.analysis.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

/** Provider-independent, defensive input for an optional cloud enrichment adapter. */
public record CloudAnalysisRequest(
    ObjectNode validatedLocalProposal,
    List<AmbiguityReason> routingReasons,
    String routingPolicyVersion,
    CloudGatewayDescriptor descriptor,
    Optional<Instant> authorizationCheckedAt,
    Optional<Instant> acceptedConsentGrantedAt,
    CloudProviderRequestToken providerRequestToken) {

  public CloudAnalysisRequest {
    Objects.requireNonNull(validatedLocalProposal, "validatedLocalProposal");
    validatedLocalProposal = validatedLocalProposal.deepCopy();
    routingReasons = List.copyOf(Objects.requireNonNull(routingReasons, "routingReasons"));
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    authorizationCheckedAt =
        Objects.requireNonNull(authorizationCheckedAt, "authorizationCheckedAt");
    acceptedConsentGrantedAt =
        Objects.requireNonNull(acceptedConsentGrantedAt, "acceptedConsentGrantedAt");
    providerRequestToken = Objects.requireNonNull(providerRequestToken, "providerRequestToken");
    if (routingPolicyVersion == null
        || routingPolicyVersion.isBlank()
        || routingPolicyVersion.codePointCount(0, routingPolicyVersion.length())
            > AnalysisProvenance.MAX_VERSION_LENGTH) {
      throw new IllegalArgumentException("routingPolicyVersion must contain 1 to 64 characters.");
    }
    validateAuthorizationSnapshot(descriptor, authorizationCheckedAt, acceptedConsentGrantedAt);
  }

  @Override
  public ObjectNode validatedLocalProposal() {
    return validatedLocalProposal.deepCopy();
  }

  private static void validateAuthorizationSnapshot(
      CloudGatewayDescriptor descriptor,
      Optional<Instant> authorizationCheckedAt,
      Optional<Instant> acceptedConsentGrantedAt) {
    if (descriptor.transferMode() == CloudTransferMode.NO_NETWORK) {
      if (authorizationCheckedAt.isPresent() || acceptedConsentGrantedAt.isPresent()) {
        throw new IllegalArgumentException(
            "A no-network gateway request cannot carry an external consent snapshot.");
      }
      return;
    }
    if (authorizationCheckedAt.isEmpty() || acceptedConsentGrantedAt.isEmpty()) {
      throw new IllegalArgumentException(
          "An external gateway request requires an accepted consent snapshot.");
    }
    if (acceptedConsentGrantedAt.get().isAfter(authorizationCheckedAt.get())) {
      throw new IllegalArgumentException(
          "Accepted consent cannot be later than the authorization check.");
    }
  }

  @Override
  public String toString() {
    return "CloudAnalysisRequest[proposal=redacted, routingReasons="
        + routingReasons
        + ", routingPolicyVersion="
        + routingPolicyVersion
        + ", descriptor="
        + descriptor
        + ", authorizationChecked="
        + authorizationCheckedAt.isPresent()
        + ", acceptedConsent="
        + acceptedConsentGrantedAt.isPresent()
        + ", providerRequestToken=redacted]";
  }
}
