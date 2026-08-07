package local.personalmemo.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import local.personalmemo.auth.application.AuthService;
import org.junit.jupiter.api.Test;

class InitialAccountBootstrapRunnerTest {
  @Test
  void usesPrivateIdentityConfigurationAndWipesBothPasswordBuffers() {
    AuthService auth = mock(AuthService.class);
    char[] password = "correct horse battery".toCharArray();
    char[] confirmation = Arrays.copyOf(password, password.length);
    FakeConsole console = new FakeConsole(List.of(), List.of(password, confirmation));

    InitialAccountBootstrapRunner.execute(
        auth,
        console,
        new InitialAccountBootstrapRunner.IdentityInput(
            "owner@example.invalid", "Example Owner", "Asia/Seoul"));

    verify(auth)
        .bootstrapInitialLocalAccount(
            eq("owner@example.invalid"), eq(password), eq("Example Owner"), eq("Asia/Seoul"));
    assertThat(password).containsOnly('\0');
    assertThat(confirmation).containsOnly('\0');
    assertThat(console.linePrompts).isEmpty();
    assertThat(console.output)
        .containsExactly("Initial account created. Sign in through the normal application login.")
        .allSatisfy(
            line -> {
              assertThat(line).doesNotContain("owner@example.invalid");
              assertThat(line).doesNotContain("Example Owner");
              assertThat(line).doesNotContain("correct horse battery");
            });
  }

  @Test
  void promptsForMissingIdentityMetadataAndDefaultsTheTimeZone() {
    AuthService auth = mock(AuthService.class);
    char[] password = "correct horse battery".toCharArray();
    char[] confirmation = Arrays.copyOf(password, password.length);
    FakeConsole console =
        new FakeConsole(
            List.of("fallback@example.invalid", "Fallback Owner", ""),
            List.of(password, confirmation));

    InitialAccountBootstrapRunner.execute(
        auth, console, new InitialAccountBootstrapRunner.IdentityInput(null, null, null));

    verify(auth)
        .bootstrapInitialLocalAccount(
            eq("fallback@example.invalid"), eq(password), eq("Fallback Owner"), eq("Asia/Seoul"));
    assertThat(console.linePrompts)
        .containsExactly("Login email: ", "Display name: ", "Time zone [Asia/Seoul]: ");
    assertThat(password).containsOnly('\0');
    assertThat(confirmation).containsOnly('\0');
  }

  @Test
  void mismatchFailsWithoutCallingTheProvisioningServiceAndStillWipesSecrets() {
    AuthService auth = mock(AuthService.class);
    char[] password = "correct horse battery".toCharArray();
    char[] confirmation = "different secure pass".toCharArray();
    FakeConsole console = new FakeConsole(List.of(), List.of(password, confirmation));

    assertThatThrownBy(
            () ->
                InitialAccountBootstrapRunner.execute(
                    auth,
                    console,
                    new InitialAccountBootstrapRunner.IdentityInput(
                        "owner@example.invalid", "Example Owner", "Asia/Seoul")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Password confirmation does not match.");

    verify(auth, never())
        .bootstrapInitialLocalAccount(
            eq("owner@example.invalid"), eq(password), eq("Example Owner"), eq("Asia/Seoul"));
    assertThat(password).containsOnly('\0');
    assertThat(confirmation).containsOnly('\0');
  }

  @Test
  void refusesToFallBackToEchoedOrPipedPasswordInput() {
    assertThatThrownBy(() -> InitialAccountBootstrapRunner.fromConsole(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("interactive terminal")
        .hasMessageContaining("environment passwords are not supported");
  }

  private static final class FakeConsole implements InitialAccountBootstrapRunner.BootstrapConsole {
    private final Deque<String> lines;
    private final Deque<char[]> passwords;
    private final List<String> linePrompts = new ArrayList<>();
    private final List<String> output = new ArrayList<>();

    private FakeConsole(List<String> lines, List<char[]> passwords) {
      this.lines = new ArrayDeque<>(lines);
      this.passwords = new ArrayDeque<>(passwords);
    }

    @Override
    public String readLine(String prompt) {
      linePrompts.add(prompt);
      return lines.removeFirst();
    }

    @Override
    public char[] readPassword(String prompt) {
      return passwords.removeFirst();
    }

    @Override
    public void println(String message) {
      output.add(message);
    }
  }
}
