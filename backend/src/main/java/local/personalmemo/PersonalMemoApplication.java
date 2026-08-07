package local.personalmemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class PersonalMemoApplication {
  private static final String INITIAL_ACCOUNT_BOOTSTRAP_COMMAND = "bootstrap-account";

  public static void main(String[] args) {
    if (args.length > 0 && INITIAL_ACCOUNT_BOOTSTRAP_COMMAND.equals(args[0])) {
      if (args.length != 1) {
        throw new IllegalArgumentException(
            "bootstrap-account accepts no command-line values; identity metadata belongs in the private environment and the password is read from the terminal.");
      }
      SpringApplication application = new SpringApplication(PersonalMemoApplication.class);
      application.setWebApplicationType(WebApplicationType.NONE);
      application.setLogStartupInfo(false);
      try (ConfigurableApplicationContext ignored =
          application.run(
              "--app.initial-account-bootstrap.command=true",
              "--app.auth.registration-enabled=false",
              "--app.auth.google.enabled=false",
              "--app.auth.google.registration-enabled=false",
              "--spring.main.banner-mode=off")) {
        // The bootstrap runner completes before SpringApplication.run returns. Closing the
        // non-web context releases the database pool and lets this one-shot process exit.
      }
      return;
    }
    SpringApplication.run(PersonalMemoApplication.class, args);
  }
}
