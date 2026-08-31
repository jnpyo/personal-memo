package local.personalmemo.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.calendar.CalendarFeedTestData.Seed;
import local.personalmemo.calendar.domain.CalendarFeedSecret;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
@TestPropertySource(
    properties = {
      "app.calendar-feed.publication.enabled=true",
      "app.calendar-feed.publication.public-origin=https://calendar.example.test",
      "app.calendar-feed.publication.consent-policy-version=calendar-feed-public-v1"
    })
class CalendarFeedPublicConsentIntegrationTest extends PostgresIntegrationTestSupport {
  private static final String CONSENT_POLICY_VERSION = "calendar-feed-public-v1";

  @BeforeEach
  void activateFixtureOwner() {
    db.sql(
            """
            update users
               set primary_email = 'calendar-public-owner@example.test',
                   primary_email_normalized = 'calendar-public-owner@example.test',
                   display_name = 'Calendar Public Owner',
                   status = 'ACTIVE'
             where id = :ownerId
            """)
        .param("ownerId", OWNER_ID)
        .update();
  }

  @Test
  void enablesExternalPublicationAtomicallyAndRequiresReconsentBeforeTitleExpansion()
      throws Exception {
    Seed event =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "외부에 공개되면 안 되는 원제목", Instant.parse("2026-09-10T09:00:00Z"), null);
    String localSecret = secret(60);
    String publicSecret = secret(61);
    String rotatedSecret = secret(62);
    JsonNode local = createFeed(event.eventId(), localSecret);
    UUID feedId = UUID.fromString(local.path("id").asText());

    assertThat(local.path("publicationScope").asText()).isEqualTo("LOCAL_ONLY");
    assertThat(local.path("publicConsentPolicyVersion").isNull()).isTrue();
    assertThat(local.path("publicConsentGrantedAt").isNull()).isTrue();
    assertGenericNotFound(publicFeed(localSecret));

    MvcResult wrongPolicy =
        enable(feedId, "public-consent-wrong-policy", 1, publicSecret, "calendar-feed-public-v0");
    assertThat(wrongPolicy.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(wrongPolicy).path("code").asText())
        .isEqualTo("CALENDAR_FEED_PUBLIC_CONSENT_POLICY_MISMATCH");
    assertFeedSecretAndVersion(feedId, localSecret, 1);

    MvcResult unchangedSecret =
        enable(feedId, "public-consent-unchanged-secret", 1, localSecret, CONSENT_POLICY_VERSION);
    assertThat(unchangedSecret.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(unchangedSecret).path("code").asText())
        .isEqualTo("CALENDAR_FEED_SECRET_UNCHANGED");
    assertFeedSecretAndVersion(feedId, localSecret, 1);

    MvcResult enabled =
        enable(feedId, "public-consent-enable", 1, publicSecret, CONSENT_POLICY_VERSION);
    MvcResult replay =
        enable(feedId, "public-consent-enable", 1, publicSecret, CONSENT_POLICY_VERSION);
    JsonNode publicDetail = response(enabled);
    assertThat(enabled.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(replay)).isEqualTo(publicDetail);
    assertThat(publicDetail.path("publicationScope").asText()).isEqualTo("PUBLIC_HTTPS");
    assertThat(publicDetail.path("publicConsentPolicyVersion").asText())
        .isEqualTo(CONSENT_POLICY_VERSION);
    assertThat(publicDetail.path("publicConsentGrantedAt").isTextual()).isTrue();
    assertThat(publicDetail.path("version").asLong()).isEqualTo(2);
    assertThat(publicDetail.toString()).doesNotContain(publicSecret, "bearerSecret", "token");
    assertFeedSecretAndVersion(feedId, publicSecret, 2);
    assertThat(
            db.sql(
                    """
                    select response_json::text
                      from idempotency_records
                     where owner_id = :ownerId
                       and operation = 'CALENDAR_FEED_EXTERNAL_PUBLICATION_ENABLE'
                       and idempotency_key = 'public-consent-enable'
                    """)
                .param("ownerId", OWNER_ID)
                .query(String.class)
                .single())
        .doesNotContain(publicSecret, "bearerSecret", "token");
    assertGenericNotFound(publicFeed(localSecret));
    assertThat(publicFeed(publicSecret).getResponse().getStatus()).isEqualTo(200);
    assertThat(calendar(publicFeed(publicSecret))).doesNotContain("외부에 공개되면 안 되는 원제목");

    MvcResult titleExpansion =
        mvc.perform(
                patch("/api/v1/calendar-feeds/{id}", feedId)
                    .header("Idempotency-Key", "public-title-expansion")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "displayName",
                                "공개 수신자",
                                "disclosureMode",
                                "TITLE",
                                "expectedVersion",
                                2))))
            .andReturn();
    assertThat(titleExpansion.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(titleExpansion).path("code").asText())
        .isEqualTo("CALENDAR_FEED_PUBLIC_DISCLOSURE_RECONSENT_REQUIRED");
    assertThat(
            db.sql("select disclosure_mode from calendar_feeds where id = :feedId")
                .param("feedId", feedId)
                .query(String.class)
                .single())
        .isEqualTo("BUSY_ONLY");
    assertFeedSecretAndVersion(feedId, publicSecret, 2);

    JsonNode renamed =
        response(
            mvc.perform(
                    patch("/api/v1/calendar-feeds/{id}", feedId)
                        .header("Idempotency-Key", "public-display-name-update")
                        .contentType("application/json")
                        .content(
                            json.writeValueAsBytes(
                                Map.of(
                                    "displayName",
                                    "이름만 변경한 공개 수신자",
                                    "disclosureMode",
                                    "BUSY_ONLY",
                                    "expectedVersion",
                                    2))))
                .andReturn());
    assertThat(renamed.path("displayName").asText()).isEqualTo("이름만 변경한 공개 수신자");
    assertThat(renamed.path("version").asLong()).isEqualTo(3);
    assertThat(renamed.path("publicationScope").asText()).isEqualTo("PUBLIC_HTTPS");

    JsonNode rotated =
        response(
            mvc.perform(
                    post("/api/v1/calendar-feeds/{id}/rotate", feedId)
                        .header("Idempotency-Key", "public-secret-rotate")
                        .contentType("application/json")
                        .content(
                            json.writeValueAsBytes(
                                Map.of("bearerSecret", rotatedSecret, "expectedVersion", 3))))
                .andReturn());
    assertThat(rotated.path("version").asLong()).isEqualTo(4);
    assertThat(rotated.path("publicationScope").asText()).isEqualTo("PUBLIC_HTTPS");
    assertThat(rotated.path("publicConsentPolicyVersion").asText())
        .isEqualTo(CONSENT_POLICY_VERSION);
    assertGenericNotFound(publicFeed(publicSecret));
    assertThat(publicFeed(rotatedSecret).getResponse().getStatus()).isEqualTo(200);

    JsonNode revoked =
        response(
            mvc.perform(
                    post("/api/v1/calendar-feeds/{id}/revoke", feedId)
                        .header("Idempotency-Key", "public-feed-revoke")
                        .contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("expectedVersion", 4))))
                .andReturn());
    assertThat(revoked.path("status").asText()).isEqualTo("REVOKED");
    assertThat(revoked.path("publicationScope").asText()).isEqualTo("LOCAL_ONLY");
    assertThat(revoked.path("publicConsentPolicyVersion").isNull()).isTrue();
    assertThat(revoked.path("publicConsentGrantedAt").isNull()).isTrue();
    assertGenericNotFound(publicFeed(rotatedSecret));
  }

  @Test
  void publicTitleFeedCannotChangeToBusyOnlyWithoutASeparateReconsentFlow() throws Exception {
    Seed event =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "공개 제목", Instant.parse("2026-09-12T09:00:00Z"), null);
    String localSecret = secret(80);
    String publicSecret = secret(81);
    UUID feedId =
        UUID.fromString(createFeed(event.eventId(), localSecret, "TITLE").path("id").asText());
    assertThat(
            enable(feedId, "title-feed-enable", 1, publicSecret, CONSENT_POLICY_VERSION)
                .getResponse()
                .getStatus())
        .isEqualTo(200);

    MvcResult disclosureChange =
        mvc.perform(
                patch("/api/v1/calendar-feeds/{id}", feedId)
                    .header("Idempotency-Key", "public-title-to-busy")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "displayName",
                                "공개 수신자",
                                "disclosureMode",
                                "BUSY_ONLY",
                                "expectedVersion",
                                2))))
            .andReturn();

    assertThat(disclosureChange.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(disclosureChange).path("code").asText())
        .isEqualTo("CALENDAR_FEED_PUBLIC_DISCLOSURE_RECONSENT_REQUIRED");
    assertThat(
            db.sql("select disclosure_mode from calendar_feeds where id = :feedId")
                .param("feedId", feedId)
                .query(String.class)
                .single())
        .isEqualTo("TITLE");
    assertFeedSecretAndVersion(feedId, publicSecret, 2);
  }

  @Test
  void publicModeRejectsLocalAndStalePolicyFeedsForReadsAndRotation() throws Exception {
    Seed event =
        CalendarFeedTestData.timed(
            db, OWNER_ID, "정책 고정 확인", Instant.parse("2026-09-11T09:00:00Z"), null);
    String localSecret = secret(70);
    String publicSecret = secret(71);
    String nextSecret = secret(72);
    UUID feedId = UUID.fromString(createFeed(event.eventId(), localSecret).path("id").asText());

    MvcResult localRotate = rotate(feedId, "local-rotate-in-public-mode", 1, nextSecret);
    assertThat(localRotate.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(localRotate).path("code").asText())
        .isEqualTo("CALENDAR_FEED_PUBLIC_CONSENT_REQUIRED");
    assertFeedSecretAndVersion(feedId, localSecret, 1);

    assertThat(
            enable(feedId, "stale-policy-enable", 1, publicSecret, CONSENT_POLICY_VERSION)
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    db.sql(
            """
            update calendar_feeds
               set public_consent_policy_version = 'calendar-feed-public-v0'
             where id = :feedId
            """)
        .param("feedId", feedId)
        .update();

    assertGenericNotFound(publicFeed(publicSecret));
    MvcResult staleRotate = rotate(feedId, "stale-policy-rotate", 2, nextSecret);
    assertThat(staleRotate.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(staleRotate).path("code").asText())
        .isEqualTo("CALENDAR_FEED_PUBLIC_CONSENT_REQUIRED");
    assertFeedSecretAndVersion(feedId, publicSecret, 2);
  }

  private JsonNode createFeed(UUID eventId, String bearerSecret) throws Exception {
    return createFeed(eventId, bearerSecret, "BUSY_ONLY");
  }

  private JsonNode createFeed(UUID eventId, String bearerSecret, String disclosureMode)
      throws Exception {
    return response(
        mvc.perform(
                post("/api/v1/calendar-feeds")
                    .header("Idempotency-Key", "create-" + bearerSecret)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "displayName",
                                "공개 수신자",
                                "disclosureMode",
                                disclosureMode,
                                "eventIds",
                                List.of(eventId),
                                "bearerSecret",
                                bearerSecret))))
            .andReturn());
  }

  private MvcResult enable(
      UUID feedId,
      String key,
      long expectedVersion,
      String bearerSecret,
      String consentPolicyVersion)
      throws Exception {
    return mvc.perform(
            post("/api/v1/calendar-feeds/{id}/external-publication/enable", feedId)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "expectedVersion",
                            expectedVersion,
                            "bearerSecret",
                            bearerSecret,
                            "consentPolicyVersion",
                            consentPolicyVersion))))
        .andReturn();
  }

  private MvcResult rotate(UUID feedId, String key, long expectedVersion, String bearerSecret)
      throws Exception {
    return mvc.perform(
            post("/api/v1/calendar-feeds/{id}/rotate", feedId)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "expectedVersion", expectedVersion,
                            "bearerSecret", bearerSecret))))
        .andReturn();
  }

  private MvcResult publicFeed(String bearerSecret) throws Exception {
    return mvc.perform(get("/calendar/v1/feed.ics").queryParam("token", bearerSecret)).andReturn();
  }

  private void assertFeedSecretAndVersion(UUID feedId, String bearerSecret, long version) {
    Map<String, Object> stored =
        db.sql("select token_verifier, version from calendar_feeds where id = :feedId")
            .param("feedId", feedId)
            .query()
            .singleRow();
    assertThat(stored.get("token_verifier"))
        .isEqualTo(CalendarFeedSecret.requireVerifier(bearerSecret));
    assertThat(((Number) stored.get("version")).longValue()).isEqualTo(version);
  }

  private String calendar(MvcResult result) {
    return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private String secret(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void assertGenericNotFound(MvcResult result) {
    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(result.getResponse().getContentType()).isNull();
  }
}
