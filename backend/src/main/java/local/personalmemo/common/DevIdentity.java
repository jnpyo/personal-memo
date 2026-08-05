package local.personalmemo.common;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DevIdentity {
  private static final UUID DEVELOPMENT_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  public UUID ownerId() {
    return DEVELOPMENT_OWNER_ID;
  }
}
