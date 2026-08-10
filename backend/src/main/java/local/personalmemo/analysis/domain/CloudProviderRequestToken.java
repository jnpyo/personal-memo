package local.personalmemo.analysis.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import local.personalmemo.common.security.Hashing;

/** Opaque, deterministic identity for retrying one server-owned gateway request. */
public record CloudProviderRequestToken(String value) {
  private static final String NAMESPACE = "personal-memo/provider-request/v1";
  private static final Pattern VALUE_PATTERN = Pattern.compile("^pmr1_[0-9a-f]{64}$");

  public CloudProviderRequestToken {
    if (value == null || !VALUE_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Cloud provider request token has an invalid format.");
    }
  }

  public static CloudProviderRequestToken issue(
      UUID ownerId, String operation, String idempotencyKey, String requestHash) {
    Objects.requireNonNull(ownerId, "ownerId");
    String canonical =
        lengthPrefixed(NAMESPACE)
            + lengthPrefixed(requireNonBlank(operation, "operation"))
            + lengthPrefixed(ownerId.toString())
            + lengthPrefixed(Hashing.sha256(requireNonBlank(idempotencyKey, "idempotencyKey")))
            + lengthPrefixed(requireRequestHash(requestHash));
    return new CloudProviderRequestToken("pmr1_" + Hashing.sha256(canonical));
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank.");
    }
    return value;
  }

  private static String requireRequestHash(String value) {
    if (value == null || !value.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 value.");
    }
    return value;
  }

  private static String lengthPrefixed(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
  }

  @Override
  public String toString() {
    return "CloudProviderRequestToken[redacted]";
  }
}
