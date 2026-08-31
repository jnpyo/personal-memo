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
    CloudProviderRequestToken providerRequestToken,
    Optional<TagRetrievalContext> tagRetrievalContext,
    Optional<LocalModelInput> localModelInput) {

  public CloudAnalysisRequest(
      ObjectNode validatedLocalProposal,
      List<AmbiguityReason> routingReasons,
      String routingPolicyVersion,
      CloudGatewayDescriptor descriptor,
      Optional<Instant> authorizationCheckedAt,
      Optional<Instant> acceptedConsentGrantedAt,
      CloudProviderRequestToken providerRequestToken) {
    this(
        validatedLocalProposal,
        routingReasons,
        routingPolicyVersion,
        descriptor,
        authorizationCheckedAt,
        acceptedConsentGrantedAt,
        providerRequestToken,
        Optional.empty(),
        Optional.empty());
  }

  public CloudAnalysisRequest(
      ObjectNode validatedLocalProposal,
      List<AmbiguityReason> routingReasons,
      String routingPolicyVersion,
      CloudGatewayDescriptor descriptor,
      Optional<Instant> authorizationCheckedAt,
      Optional<Instant> acceptedConsentGrantedAt,
      CloudProviderRequestToken providerRequestToken,
      Optional<TagRetrievalContext> tagRetrievalContext) {
    this(
        validatedLocalProposal,
        routingReasons,
        routingPolicyVersion,
        descriptor,
        authorizationCheckedAt,
        acceptedConsentGrantedAt,
        providerRequestToken,
        tagRetrievalContext,
        Optional.empty());
  }

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
    tagRetrievalContext = Objects.requireNonNull(tagRetrievalContext, "tagRetrievalContext");
    localModelInput = Objects.requireNonNull(localModelInput, "localModelInput");
    if (routingPolicyVersion == null
        || routingPolicyVersion.isBlank()
        || routingPolicyVersion.codePointCount(0, routingPolicyVersion.length())
            > AnalysisProvenance.MAX_VERSION_LENGTH) {
      throw new IllegalArgumentException("routingPolicyVersion must contain 1 to 64 characters.");
    }
    validateTransferBoundary(
        descriptor, authorizationCheckedAt, acceptedConsentGrantedAt, localModelInput);
  }

  @Override
  public ObjectNode validatedLocalProposal() {
    return validatedLocalProposal.deepCopy();
  }

  private static void validateTransferBoundary(
      CloudGatewayDescriptor descriptor,
      Optional<Instant> authorizationCheckedAt,
      Optional<Instant> acceptedConsentGrantedAt,
      Optional<LocalModelInput> localModelInput) {
    switch (descriptor.transferMode()) {
      case NO_NETWORK -> {
        requireNoExternalConsent(authorizationCheckedAt, acceptedConsentGrantedAt);
        if (localModelInput.isPresent()) {
          throw new IllegalArgumentException(
              "A no-network gateway request cannot carry local-model memo content.");
        }
      }
      case LOCAL_MACHINE_MEMO_CONTENT -> {
        requireNoExternalConsent(authorizationCheckedAt, acceptedConsentGrantedAt);
        if (localModelInput.isEmpty()) {
          throw new IllegalArgumentException(
              "A machine-local gateway request requires bounded local-model input.");
        }
      }
      case EXTERNAL_MEMO_CONTENT -> {
        if (localModelInput.isPresent()) {
          throw new IllegalArgumentException(
              "An external gateway request cannot carry machine-local model input.");
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
    }
  }

  private static void requireNoExternalConsent(
      Optional<Instant> authorizationCheckedAt, Optional<Instant> acceptedConsentGrantedAt) {
    if (authorizationCheckedAt.isPresent() || acceptedConsentGrantedAt.isPresent()) {
      throw new IllegalArgumentException(
          "A machine-local gateway request cannot carry an external consent snapshot.");
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
        + ", providerRequestToken=redacted, tagRetrievalContext="
        + tagRetrievalContext
            .map(
                context ->
                    context.version() + "/" + context.candidateCount() + " candidates/redacted")
            .orElse("absent")
        + ", localModelInput="
        + (localModelInput.isPresent() ? "present/redacted" : "absent")
        + "]";
  }
}
