package local.personalmemo.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.security.Hashing;
import org.junit.jupiter.api.Test;

class CalendarFeedSecretTest {
  @Test
  void acceptsOnlyCanonicalThirtyTwoByteBase64UrlAndUsesTheVersionedDomain() {
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    assertThat(secret).hasSize(43);
    assertThat(CalendarFeedSecret.requireVerifier(secret))
        .isEqualTo(Hashing.sha256("calendar-feed-bearer-v1\u0000" + secret));
  }

  @Test
  void rejectsNonCanonicalWrongLengthAndMalformedSecrets() {
    String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    assertThat(CalendarFeedSecret.verifier(canonical + "=")).isEmpty();
    assertThat(CalendarFeedSecret.verifier(canonical.substring(1))).isEmpty();
    assertThat(CalendarFeedSecret.verifier("!".repeat(43))).isEmpty();
    assertThat(CalendarFeedSecret.verifier(null)).isEmpty();
    assertThatThrownBy(() -> CalendarFeedSecret.requireVerifier("!".repeat(43)))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_CALENDAR_FEED_SECRET"));
  }

  @Test
  void mapsEveryInvalidPublicLookupToOneFixedVerifierShape() {
    assertThat(CalendarFeedSecret.lookupVerifier(null)).isEqualTo("0".repeat(64));
    assertThat(CalendarFeedSecret.lookupVerifier("short")).isEqualTo("0".repeat(64));
    assertThat(CalendarFeedSecret.lookupVerifier("!".repeat(43))).isEqualTo("0".repeat(64));
  }
}
