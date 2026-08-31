package local.personalmemo.calendar.domain;

import java.util.Base64;
import java.util.Optional;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.security.Hashing;

public final class CalendarFeedSecret {
  private static final String DOMAIN = "calendar-feed-bearer-v1\u0000";
  private static final String INVALID_LOOKUP_VERIFIER = "0".repeat(64);
  private static final int ENCODED_LENGTH = 43;
  private static final int DECODED_LENGTH = 32;

  private CalendarFeedSecret() {}

  public static String requireVerifier(String secret) {
    return verifier(secret)
        .orElseThrow(
            () ->
                DomainException.invalid(
                    "INVALID_CALENDAR_FEED_SECRET",
                    "The calendar feed secret must be canonical 32-byte Base64url."));
  }

  public static Optional<String> verifier(String secret) {
    if (secret == null || secret.length() != ENCODED_LENGTH) {
      return Optional.empty();
    }
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(secret);
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
    if (decoded.length != DECODED_LENGTH
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(secret)) {
      return Optional.empty();
    }
    return Optional.of(Hashing.sha256(DOMAIN + secret));
  }

  public static String lookupVerifier(String secret) {
    return verifier(secret).orElse(INVALID_LOOKUP_VERIFIER);
  }
}
