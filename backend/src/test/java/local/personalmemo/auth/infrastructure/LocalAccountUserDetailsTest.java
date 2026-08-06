package local.personalmemo.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.UUID;
import local.personalmemo.auth.domain.UserAccount;
import org.junit.jupiter.api.Test;

class LocalAccountUserDetailsTest {
  @Test
  void canRoundTripThroughJavaSerializationForSessionStorage() throws Exception {
    var account =
        new UserAccount(
            UUID.randomUUID(), "owner@example.com", "owner@example.com", "Owner", "ACTIVE");
    var details =
        new LocalAccountUserDetails(
            account, "bcrypt-password-hash", Instant.parse("2026-08-06T00:00:00Z"));

    byte[] serialized;
    try (var bytes = new ByteArrayOutputStream();
        var output = new ObjectOutputStream(bytes)) {
      output.writeObject(details);
      serialized = bytes.toByteArray();
    }

    LocalAccountUserDetails restored;
    try (var input = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
      restored = (LocalAccountUserDetails) input.readObject();
    }

    assertThat(restored.account()).isEqualTo(account);
    assertThat(restored.getUsername()).isEqualTo("owner@example.com");
    assertThat(restored.getPassword()).isEqualTo("bcrypt-password-hash");
  }
}
