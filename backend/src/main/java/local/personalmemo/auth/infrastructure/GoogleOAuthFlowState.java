package local.personalmemo.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

/** Distinguishes an explicit account-link authorization from an ordinary Google login. */
final class GoogleOAuthFlowState {
  private static final String LINK_PREFIX = "pm1.link.";
  private static final Duration LINK_INTENT_TTL = Duration.ofMinutes(10);

  private GoogleOAuthFlowState() {}

  static String markLink(String state) {
    if (state == null || state.isBlank()) {
      throw new IllegalArgumentException("OAuth state is required.");
    }
    return isLink(state) ? state : LINK_PREFIX + state;
  }

  static boolean isLink(String state) {
    return state != null && state.startsWith(LINK_PREFIX);
  }

  static boolean matchesLink(String expected, String actual) {
    if (expected == null || actual == null || !isLink(actual)) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  static boolean isExpired(Instant createdAt, Instant now) {
    return createdAt.plus(LINK_INTENT_TTL).isBefore(now);
  }
}
