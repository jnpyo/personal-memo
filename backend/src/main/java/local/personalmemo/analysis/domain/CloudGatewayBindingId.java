package local.personalmemo.analysis.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import local.personalmemo.common.security.Hashing;

/**
 * Non-secret deterministic identity for one descriptor-bound gateway execution contract.
 *
 * <p>An adapter must change its gateway version whenever executable request semantics or routing
 * configuration changes. Credentials and other secrets are deliberately not fingerprinted.
 */
public record CloudGatewayBindingId(String value) {
  private static final String NAMESPACE = "personal-memo/cloud-gateway-binding/v1";
  private static final Pattern VALUE_PATTERN = Pattern.compile("^cgb1_[0-9a-f]{64}$");

  public CloudGatewayBindingId {
    if (value == null || !VALUE_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Cloud gateway binding ID has an invalid format.");
    }
  }

  public static CloudGatewayBindingId issue(CloudGatewayDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    String canonical =
        lengthPrefixed(NAMESPACE)
            + lengthPrefixed(descriptor.gatewayVersion())
            + lengthPrefixed(descriptor.providerId())
            + lengthPrefixed(descriptor.modelVersion())
            + lengthPrefixed(descriptor.consentPolicyVersion())
            + lengthPrefixed(descriptor.transferMode().name());
    return new CloudGatewayBindingId("cgb1_" + Hashing.sha256(canonical));
  }

  private static String lengthPrefixed(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }
}
