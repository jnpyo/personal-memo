package local.personalmemo.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;

class IcalendarSerializerTest {
  private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final Instant CREATED_AT = Instant.parse("2026-08-25T01:02:03.987654Z");

  private final IcalendarSerializer serializer = new IcalendarSerializer();

  @Test
  void serializesDeterministicTimedAndAllDayEventsWithoutLeakingInternalIds() {
    UUID timedId = UUID.fromString("20000000-0000-4000-8000-000000000001");
    UUID openEndedId = UUID.fromString("20000000-0000-4000-8000-000000000002");
    UUID allDayId = UUID.fromString("20000000-0000-4000-8000-000000000003");
    String title = "긴 한글 일정 😀, 세미콜론; 경로\\메모\r\nEND:VEVENT\nX-INJECT:yes ".repeat(4);
    List<CanonicalScheduledEvent> events =
        List.of(
            timed(
                timedId,
                title,
                Instant.parse("2026-09-01T09:00:00Z"),
                Instant.parse("2026-09-01T10:30:00Z")),
            timed(openEndedId, "종료 미정", Instant.parse("2026-09-02T09:00:00Z"), null),
            allDay(
                allDayId, "학회 일정", LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-05")));

    byte[] first = serializer.serialize(OWNER_ID, events);
    byte[] second = serializer.serialize(OWNER_ID, events);
    String calendar = new String(first, StandardCharsets.UTF_8);
    String unfolded = calendar.replace("\r\n ", "");

    assertThat(second).containsExactly(first);
    assertThat(calendar)
        .startsWith("BEGIN:VCALENDAR\r\n")
        .endsWith("END:VCALENDAR\r\n")
        .contains("VERSION:2.0\r\n")
        .contains("DTSTAMP:20260825T010203Z\r\n")
        .contains("DTSTART:20260901T090000Z\r\n")
        .contains("DTEND:20260901T103000Z\r\n")
        .contains("DTSTART:20260902T090000Z\r\n")
        .contains("DTSTART;VALUE=DATE:20260903\r\n")
        .contains("DTEND;VALUE=DATE:20260905\r\n")
        .doesNotContain("VALARM", "DESCRIPTION:", "LOCATION:", "METHOD:");
    assertThat(unfolded)
        .contains("SUMMARY:긴 한글 일정 😀\\, 세미콜론\\; 경로\\\\메모\\nEND:VEVENT\\nX-INJECT:yes ")
        .doesNotContain("\r\nX-INJECT:yes", "\r\nEND:VEVENT\r\nX-INJECT:yes");
    assertThat(calendar)
        .doesNotContain(
            OWNER_ID.toString(), timedId.toString(), openEndedId.toString(), allDayId.toString());
    assertThat(count(calendar, "BEGIN:VEVENT\r\n")).isEqualTo(3);
    assertThat(count(calendar, "DTEND:")).isEqualTo(1);
    assertThat(first[0]).isEqualTo((byte) 'B');
    for (String line : calendar.split("\r\n", -1)) {
      assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
    }
  }

  @Test
  void rejectsFractionalScheduleInstantsInsteadOfRoundingOrTruncatingThem() {
    CanonicalScheduledEvent event =
        timed(UUID.randomUUID(), "fractional", Instant.parse("2026-09-01T09:00:00.100Z"), null);

    assertThatThrownBy(() -> serializer.serialize(OWNER_ID, List.of(event)))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_ICALENDAR_EVENT"));
  }

  @Test
  void rejectsUnsupportedControlsUnpairedSurrogatesAndOutOfRangeYears() {
    List<String> unsafeTitles =
        List.of("control\u0001text", new String(new char[] {'x', '\ud800'}));
    for (String unsafeTitle : unsafeTitles) {
      CanonicalScheduledEvent event =
          timed(UUID.randomUUID(), unsafeTitle, Instant.parse("2026-09-01T09:00:00Z"), null);
      assertThatThrownBy(() -> serializer.serialize(OWNER_ID, List.of(event)))
          .isInstanceOfSatisfying(
              DomainException.class,
              exception -> assertThat(exception.code()).isEqualTo("ICALENDAR_UNSAFE_TEXT"));
    }

    CanonicalScheduledEvent outOfRange =
        allDay(UUID.randomUUID(), "far future", LocalDate.of(10_000, 1, 1), null);
    assertThatThrownBy(() -> serializer.serialize(OWNER_ID, List.of(outOfRange)))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_ICALENDAR_EVENT"));
  }

  @Test
  void rejectsACompletedDocumentAboveTheBoundedResponseSize() {
    CanonicalScheduledEvent oversized =
        timed(UUID.randomUUID(), "가".repeat(50_000), Instant.parse("2026-09-01T09:00:00Z"), null);

    assertThatThrownBy(() -> serializer.serialize(OWNER_ID, List.of(oversized)))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("ICALENDAR_EXPORT_TOO_LARGE"));
  }

  private CanonicalScheduledEvent timed(UUID id, String title, Instant start, Instant end) {
    return new CanonicalScheduledEvent(
        id, title, "TIMED", start, end, null, null, "Asia/Seoul", CREATED_AT);
  }

  private CanonicalScheduledEvent allDay(
      UUID id, String title, LocalDate start, LocalDate endExclusive) {
    return new CanonicalScheduledEvent(
        id, title, "ALL_DAY", null, null, start, endExclusive, "Asia/Seoul", CREATED_AT);
  }

  private int count(String value, String needle) {
    return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
  }
}
