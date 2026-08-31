package local.personalmemo.calendar.domain;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public final class CalendarFeedOpaqueIdGenerator {
  private static final int RANDOM_BYTES = 32;
  private final SecureRandom random = new SecureRandom();

  public String publicUid() {
    byte[] bytes = new byte[RANDOM_BYTES];
    random.nextBytes(bytes);
    return "pm-feed-v1-"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        + "@personal-memo.invalid";
  }
}
