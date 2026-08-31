package local.personalmemo.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;

class RecipientIcalendarSerializerTest {
  private static final String UID =
      "pm-feed-v1-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA@personal-memo.invalid";
  private static final Instant UPDATED_AT = Instant.parse("2026-08-25T01:02:03Z");

  private final RecipientIcalendarSerializer serializer = new RecipientIcalendarSerializer();

  @Test
  void emitsTitleWithRfcTextEscapingUtf8FoldingAndUtcWholeSeconds() {
    RecipientCalendarEvent event = timed("회의, 준비; 확인\\완료\r\n" + "한글일정".repeat(20), "ACTIVE", 3);

    String calendar =
        new String(serializer.serialize("TITLE", List.of(event)), StandardCharsets.UTF_8);

    assertThat(unfold(calendar))
        .contains(
            "UID:" + UID + "\r\n",
            "DTSTAMP:20260825T010203Z\r\n",
            "SEQUENCE:3\r\n",
            "DTSTART:20260901T090000Z\r\n",
            "DTEND:20260901T100000Z\r\n",
            "SUMMARY:회의\\, 준비\\; 확인\\\\완료\\n")
        .doesNotContain("\r\nSTATUS:CANCELLED");
    assertThat(calendar.split("\r\n", -1))
        .allSatisfy(
            line ->
                assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75));
  }

  @Test
  void hidesTitlesInBusyModeAndKeepsUidWhileCancellationOmitsSummary() {
    String busy =
        new String(
            serializer.serialize("BUSY_ONLY", List.of(timed("private title", "ACTIVE", 7))),
            StandardCharsets.UTF_8);
    String cancelled =
        new String(
            serializer.serialize("TITLE", List.of(timed(null, "CANCELLED", 8))),
            StandardCharsets.UTF_8);

    assertThat(unfold(busy))
        .contains("UID:" + UID, "SEQUENCE:7", "SUMMARY:Busy")
        .doesNotContain("private title");
    assertThat(unfold(cancelled))
        .contains("UID:" + UID, "SEQUENCE:8", "STATUS:CANCELLED")
        .doesNotContain("SUMMARY:");
  }

  @Test
  void enforcesRfcYearAndTheOneHundredTwentyEightKibibyteResponseLimit() {
    RecipientCalendarEvent yearZero =
        new RecipientCalendarEvent(
            UID,
            "year zero",
            "ACTIVE",
            0,
            "ALL_DAY",
            null,
            null,
            LocalDate.of(0, 1, 1),
            null,
            "UTC",
            UPDATED_AT);

    assertThatThrownBy(() -> serializer.serialize("TITLE", List.of(yearZero)))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_CALENDAR_FEED_EVENT"));
    assertThatThrownBy(
            () -> serializer.serialize("TITLE", List.of(timed("가".repeat(50_000), "ACTIVE", 0))))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("CALENDAR_FEED_TOO_LARGE"));
  }

  private RecipientCalendarEvent timed(String title, String state, int sequence) {
    return new RecipientCalendarEvent(
        UID,
        title,
        state,
        sequence,
        "TIMED",
        Instant.parse("2026-09-01T09:00:00Z"),
        Instant.parse("2026-09-01T10:00:00Z"),
        null,
        null,
        "Asia/Seoul",
        UPDATED_AT);
  }

  private String unfold(String value) {
    return value.replace("\r\n ", "");
  }
}
