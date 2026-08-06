package local.personalmemo.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {
  private AuthDtos() {}

  public record Register(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 128) String password,
      @NotBlank @Size(max = 80) String displayName,
      @NotBlank @Size(max = 64) String timeZone) {}

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

  public record AuthSession(
      UUID userId, String email, String displayName, List<String> loginMethods) {
    public AuthSession {
      loginMethods = List.copyOf(loginMethods);
    }
  }

  public record Capabilities(
      boolean registrationEnabled, boolean googleEnabled, boolean googleRegistrationEnabled) {}

  public record Csrf(String headerName, String parameterName, String token) {}

  public record GoogleLinkIntent(String authorizationUrl) {}
}
