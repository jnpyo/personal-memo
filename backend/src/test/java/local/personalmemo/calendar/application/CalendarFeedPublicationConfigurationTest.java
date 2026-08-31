package local.personalmemo.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CalendarFeedPublicationConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(CalendarFeedPublicationConfiguration.class);

  @Test
  void defaultsToDisabledWithNoPublicOrigin() {
    runner.run(
        context -> {
          assertThat(context.getStartupFailure()).isNull();
          CalendarFeedPublicationProperties properties =
              context.getBean(CalendarFeedPublicationProperties.class);
          assertThat(properties.enabled()).isFalse();
          assertThat(properties.publicOrigin()).isEmpty();
          assertThat(properties.consentPolicyVersion()).isEmpty();
        });
  }

  @Test
  void bindsAnExplicitCanonicalPublicHttpsOrigin() {
    runner
        .withPropertyValues(
            "app.calendar-feed.publication.enabled=true",
            "app.calendar-feed.publication.public-origin=https://calendar.example.com:8443",
            "app.calendar-feed.publication.consent-policy-version=calendar-feed-public-v1")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              CalendarFeedPublicationProperties properties =
                  context.getBean(CalendarFeedPublicationProperties.class);
              assertThat(properties.enabled()).isTrue();
              assertThat(properties.publicOrigin()).isEqualTo("https://calendar.example.com:8443");
              assertThat(properties.consentPolicyVersion()).isEqualTo("calendar-feed-public-v1");
            });
  }

  @Test
  void rejectsAnOriginWhenPublicationIsDisabled() {
    assertRejected(
        "app.calendar-feed.publication.enabled=false",
        "app.calendar-feed.publication.public-origin=https://calendar.example.com");
  }

  @Test
  void rejectsAConsentPolicyVersionWhenPublicationIsDisabled() {
    assertRejected(
        "app.calendar-feed.publication.enabled=false",
        "app.calendar-feed.publication.consent-policy-version=calendar-feed-public-v1");
  }

  @Test
  void rejectsMissingOrNonCanonicalConsentPolicyVersionsWhenPublicationIsEnabled() {
    String[] invalidPolicies = {
      "", "Calendar-Feed-Public-v1", "calendar public v1", "v1/terms", "a".repeat(65)
    };
    for (String policy : invalidPolicies) {
      assertRejected(
          "app.calendar-feed.publication.enabled=true",
          "app.calendar-feed.publication.public-origin=https://calendar.example.com",
          "app.calendar-feed.publication.consent-policy-version=" + policy);
    }
  }

  @Test
  void rejectsNonCanonicalOrNonPublicOriginsWhenPublicationIsEnabled() {
    String[] invalidOrigins = {
      "",
      "http://calendar.example.com",
      "HTTPS://calendar.example.com",
      "https://Calendar.example.com",
      "https://calendar.example.com/",
      "https://calendar.example.com/path",
      "https://calendar.example.com?token=value",
      "https://calendar.example.com#fragment",
      "https://user@calendar.example.com",
      "https://localhost",
      "https://calendar.localhost",
      "https://calendar",
      "https://127.0.0.1",
      "https://127.1",
      "https://127.0.1",
      "https://0x7f.0.0.1",
      "https://[2001:db8::1]",
      "https://calendar.123",
      "https://calendar_ex.example.com",
      "https://calendar.예시",
      "https://calendar.example.com:443",
      "https://calendar.example.com:0"
    };

    for (String origin : invalidOrigins) {
      assertRejected(
          "app.calendar-feed.publication.enabled=true",
          "app.calendar-feed.publication.public-origin=" + origin,
          "app.calendar-feed.publication.consent-policy-version=calendar-feed-public-v1");
    }
  }

  @Test
  void rejectsAnOriginOverTheApplicationLengthLimit() {
    String host =
        "a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(56);
    assertThat(("https://" + host).length()).isEqualTo(256);
    assertRejected(
        "app.calendar-feed.publication.enabled=true",
        "app.calendar-feed.publication.public-origin=https://" + host,
        "app.calendar-feed.publication.consent-policy-version=calendar-feed-public-v1");
  }

  @Test
  void rejectsSurroundingWhitespaceAtTheValidatedRecordBoundary() {
    assertThatThrownBy(
            () ->
                new CalendarFeedPublicationProperties(
                    true, " https://calendar.example.com", "calendar-feed-public-v1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CalendarFeedPublicationProperties(
                    true, "https://calendar.example.com", " calendar-feed-public-v1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void assertRejected(String... properties) {
    runner
        .withPropertyValues(properties)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .as("rejected properties %s", String.join(", ", properties))
                    .isNotNull());
  }
}
