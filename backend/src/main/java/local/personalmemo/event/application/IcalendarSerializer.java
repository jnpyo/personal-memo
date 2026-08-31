package local.personalmemo.event.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.security.Hashing;
import org.springframework.stereotype.Component;

@Component
public class IcalendarSerializer {
  private static final int MAX_DOCUMENT_BYTES = 128 * 1024;
  private static final int MAX_PHYSICAL_LINE_OCTETS = 75;
  private static final String CRLF = "\r\n";
  private static final DateTimeFormatter UTC_DATE_TIME =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

  public byte[] serialize(UUID ownerId, List<CanonicalScheduledEvent> events) {
    if (ownerId == null || events == null || events.isEmpty()) {
      throw invalidEvent();
    }

    StringBuilder calendar = new StringBuilder();
    appendLine(calendar, "BEGIN:VCALENDAR");
    appendLine(calendar, "PRODID:-//Personal Memo//Authenticated iCalendar Export 1.0//EN");
    appendLine(calendar, "VERSION:2.0");
    appendLine(calendar, "CALSCALE:GREGORIAN");
    for (CanonicalScheduledEvent event : events) {
      appendEvent(calendar, ownerId, event);
    }
    appendLine(calendar, "END:VCALENDAR");

    byte[] encoded = calendar.toString().getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_DOCUMENT_BYTES) {
      throw DomainException.invalid(
          "ICALENDAR_EXPORT_TOO_LARGE", "The calendar export exceeds the safe response size.");
    }
    return encoded;
  }

  private void appendEvent(StringBuilder calendar, UUID ownerId, CanonicalScheduledEvent event) {
    if (event == null || event.id() == null || event.createdAt() == null) {
      throw invalidEvent();
    }
    requireRfcYear(event.createdAt());
    String summary = escapeText(event.title());

    appendLine(calendar, "BEGIN:VEVENT");
    appendLine(calendar, "UID:" + opaqueUid(ownerId, event.id()));
    appendLine(calendar, "DTSTAMP:" + utc(event.createdAt()));
    appendLine(calendar, "SEQUENCE:0");
    switch (event.scheduleKind()) {
      case "TIMED" -> appendTimedSchedule(calendar, event);
      case "ALL_DAY" -> appendAllDaySchedule(calendar, event);
      default -> throw invalidEvent();
    }
    appendLine(calendar, "SUMMARY:" + summary);
    appendLine(calendar, "END:VEVENT");
  }

  private void appendTimedSchedule(StringBuilder calendar, CanonicalScheduledEvent event) {
    require(
        event.startAt() != null && event.startDate() == null && event.endDateExclusive() == null);
    requireWholeSecond(event.startAt());
    requireWholeSecond(event.endAt());
    requireRfcYear(event.startAt());
    requireRfcYear(event.endAt());
    require(event.endAt() == null || event.endAt().isAfter(event.startAt()));
    appendLine(calendar, "DTSTART:" + utc(event.startAt()));
    if (event.endAt() != null) {
      appendLine(calendar, "DTEND:" + utc(event.endAt()));
    }
  }

  private void appendAllDaySchedule(StringBuilder calendar, CanonicalScheduledEvent event) {
    require(event.startDate() != null && event.startAt() == null && event.endAt() == null);
    requireRfcYear(event.startDate());
    requireRfcYear(event.endDateExclusive());
    require(
        event.endDateExclusive() == null || event.endDateExclusive().isAfter(event.startDate()));
    appendLine(calendar, "DTSTART;VALUE=DATE:" + date(event.startDate()));
    if (event.endDateExclusive() != null) {
      appendLine(calendar, "DTEND;VALUE=DATE:" + date(event.endDateExclusive()));
    }
  }

  private String opaqueUid(UUID ownerId, UUID eventId) {
    String digest =
        Hashing.sha256("authenticated-icalendar-event-v1\u0000" + ownerId + "\u0000" + eventId);
    return "pm-auth-v1-" + digest + "@personal-memo.invalid";
  }

  private String utc(Instant value) {
    return UTC_DATE_TIME.format(value.truncatedTo(ChronoUnit.SECONDS));
  }

  private String date(LocalDate value) {
    return DATE.format(value);
  }

  private String escapeText(String value) {
    if (value == null) {
      throw invalidEvent();
    }
    validateText(value);
    StringBuilder escaped = new StringBuilder(value.length());
    for (int offset = 0; offset < value.length(); ) {
      char current = value.charAt(offset);
      if (current == '\r') {
        if (offset + 1 < value.length() && value.charAt(offset + 1) == '\n') {
          offset++;
        }
        escaped.append("\\n");
      } else if (current == '\n') {
        escaped.append("\\n");
      } else if (current == '\\' || current == ',' || current == ';') {
        escaped.append('\\').append(current);
      } else {
        int codePoint = value.codePointAt(offset);
        escaped.appendCodePoint(codePoint);
        offset += Character.charCount(codePoint) - 1;
      }
      offset++;
    }
    return escaped.toString();
  }

  private void validateText(String value) {
    for (int offset = 0; offset < value.length(); offset++) {
      char current = value.charAt(offset);
      if (Character.isHighSurrogate(current)) {
        if (offset + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(offset + 1))) {
          throw unsafeText();
        }
        offset++;
        continue;
      }
      if (Character.isLowSurrogate(current)
          || (current < 0x20 && current != '\t' && current != '\r' && current != '\n')
          || current == 0x7f) {
        throw unsafeText();
      }
    }
  }

  private void appendLine(StringBuilder target, String logicalLine) {
    int physicalLineOctets = 0;
    for (int offset = 0; offset < logicalLine.length(); ) {
      int codePoint = logicalLine.codePointAt(offset);
      String encodedCodePoint = new String(Character.toChars(codePoint));
      int octets = encodedCodePoint.getBytes(StandardCharsets.UTF_8).length;
      if (physicalLineOctets + octets > MAX_PHYSICAL_LINE_OCTETS) {
        target.append(CRLF).append(' ');
        physicalLineOctets = 1;
      }
      target.append(encodedCodePoint);
      physicalLineOctets += octets;
      offset += Character.charCount(codePoint);
    }
    target.append(CRLF);
  }

  private void requireWholeSecond(Instant value) {
    require(value == null || value.getNano() == 0);
  }

  private void requireRfcYear(Instant value) {
    require(
        value == null
            || (value.atOffset(ZoneOffset.UTC).getYear() >= 1
                && value.atOffset(ZoneOffset.UTC).getYear() <= 9999));
  }

  private void requireRfcYear(LocalDate value) {
    require(value == null || (value.getYear() >= 1 && value.getYear() <= 9999));
  }

  private void require(boolean condition) {
    if (!condition) {
      throw invalidEvent();
    }
  }

  private DomainException invalidEvent() {
    return DomainException.invalid(
        "INVALID_ICALENDAR_EVENT", "A calendar event cannot be serialized safely.");
  }

  private DomainException unsafeText() {
    return DomainException.invalid(
        "ICALENDAR_UNSAFE_TEXT", "A calendar title contains unsupported control text.");
  }
}
