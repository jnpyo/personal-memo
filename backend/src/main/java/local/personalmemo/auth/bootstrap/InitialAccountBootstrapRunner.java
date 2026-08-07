package local.personalmemo.auth.bootstrap;

import java.io.Console;
import java.util.Arrays;
import local.personalmemo.auth.application.AuthService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

@Component
@ConditionalOnProperty(
    name = "app.initial-account-bootstrap.command",
    havingValue = "true",
    matchIfMissing = false)
public final class InitialAccountBootstrapRunner implements ApplicationRunner {
  private static final String DEFAULT_TIME_ZONE = "Asia/Seoul";

  private final AuthService auth;
  private final ApplicationContext applicationContext;
  private final Environment environment;

  public InitialAccountBootstrapRunner(
      AuthService auth, ApplicationContext applicationContext, Environment environment) {
    this.auth = auth;
    this.applicationContext = applicationContext;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (applicationContext instanceof WebApplicationContext) {
      throw new IllegalStateException("Initial-account bootstrap must run without an HTTP server.");
    }
    execute(
        auth,
        fromConsole(System.console()),
        new IdentityInput(
            environment.getProperty("PERSONAL_MEMO_BOOTSTRAP_EMAIL"),
            environment.getProperty("PERSONAL_MEMO_BOOTSTRAP_DISPLAY_NAME"),
            environment.getProperty("PERSONAL_MEMO_BOOTSTRAP_TIME_ZONE")));
  }

  static void execute(AuthService auth, BootstrapConsole console, IdentityInput configured) {
    String email = configuredValueOrPrompt(configured.email(), console, "Login email: ");
    String displayName =
        configuredValueOrPrompt(configured.displayName(), console, "Display name: ");
    String suppliedTimeZone =
        configuredValueOrPrompt(configured.timeZone(), console, "Time zone [Asia/Seoul]: ", true);
    String timeZone = suppliedTimeZone.isBlank() ? DEFAULT_TIME_ZONE : suppliedTimeZone.trim();

    char[] password = null;
    char[] confirmation = null;
    try {
      password = requirePassword(console.readPassword("Password: "));
      confirmation = requirePassword(console.readPassword("Confirm password: "));
      if (!Arrays.equals(password, confirmation)) {
        throw new IllegalArgumentException("Password confirmation does not match.");
      }
      auth.bootstrapInitialLocalAccount(email, password, displayName, timeZone);
      console.println("Initial account created. Sign in through the normal application login.");
    } finally {
      wipe(password);
      wipe(confirmation);
    }
  }

  static BootstrapConsole fromConsole(Console console) {
    if (console == null) {
      throw new IllegalStateException(
          "An interactive terminal is required; piped input and environment passwords are not supported.");
    }
    return new SystemBootstrapConsole(console);
  }

  private static String requireLine(String value) {
    if (value == null) {
      throw new IllegalStateException("Interactive input ended before provisioning completed.");
    }
    return value;
  }

  private static String configuredValueOrPrompt(
      String configured, BootstrapConsole console, String prompt) {
    return configuredValueOrPrompt(configured, console, prompt, false);
  }

  private static String configuredValueOrPrompt(
      String configured, BootstrapConsole console, String prompt, boolean allowBlank) {
    if (configured != null && (!configured.isBlank() || allowBlank)) {
      return configured;
    }
    return requireLine(console.readLine(prompt));
  }

  private static char[] requirePassword(char[] value) {
    if (value == null) {
      throw new IllegalStateException(
          "Interactive password input ended before provisioning completed.");
    }
    return value;
  }

  private static void wipe(char[] value) {
    if (value != null) {
      Arrays.fill(value, '\0');
    }
  }

  interface BootstrapConsole {
    String readLine(String prompt);

    char[] readPassword(String prompt);

    void println(String message);
  }

  record IdentityInput(String email, String displayName, String timeZone) {}

  private record SystemBootstrapConsole(Console console) implements BootstrapConsole {
    @Override
    public String readLine(String prompt) {
      return console.readLine("%s", prompt);
    }

    @Override
    public char[] readPassword(String prompt) {
      return console.readPassword("%s", prompt);
    }

    @Override
    public void println(String message) {
      console.writer().println(message);
      console.writer().flush();
    }
  }
}
