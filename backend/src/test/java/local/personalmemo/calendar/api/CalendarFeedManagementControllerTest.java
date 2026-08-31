package local.personalmemo.calendar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import local.personalmemo.calendar.application.CalendarFeedManagementService;
import local.personalmemo.calendar.application.CalendarFeedPublicationProperties;
import org.junit.jupiter.api.Test;

class CalendarFeedManagementControllerTest {
  @Test
  void reportsLocalOnlyWithoutLeakingAnEmptyConfiguredOrigin() {
    var response = controller(false, "").capabilities();

    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody())
        .isEqualTo(new CalendarFeedDtos.Capabilities("LOCAL_ONLY", null, null));
  }

  @Test
  void reportsTheServerOwnedOriginOnlyWhenPublicHttpsPublicationIsEnabled() {
    var response = controller(true, "https://calendar.example.com:8443").capabilities();

    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody())
        .isEqualTo(
            new CalendarFeedDtos.Capabilities(
                "PUBLIC_HTTPS", "https://calendar.example.com:8443", "calendar-feed-public-v1"));
  }

  private CalendarFeedManagementController controller(boolean enabled, String origin) {
    return new CalendarFeedManagementController(
        mock(CalendarFeedManagementService.class),
        new CalendarFeedPublicationProperties(
            enabled, origin, enabled ? "calendar-feed-public-v1" : ""));
  }
}
