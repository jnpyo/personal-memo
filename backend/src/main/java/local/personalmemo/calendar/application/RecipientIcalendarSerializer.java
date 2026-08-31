package local.personalmemo.calendar.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;

@Component
public final class RecipientIcalendarSerializer {
  private static final int MAX_DOCUMENT_BYTES = 128 * 1024;
  private static final int MAX_PHYSICAL_LINE_OCTETS = 75;
  private static final String CRLF = "\r\n";
  private static final DateTimeFormatter UTC_DATE_TIME =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

  public byte[] serialize(String disclosureMode, List<RecipientCalendarEvent> events) {
    if (!("TITLE".equals(disclosureMode) || "BUSY_ONLY".equals(disclosureMode))
        || events == null
        || events.isEmpty()) {
      throw invalidEvent();
    }
    StringBuilder calendar = new StringBuilder();
    appendLine(calendar, "BEGIN:VCALENDAR");
    appendLine(calendar, "PRODID:-//Personal Memo//Recipient iCalendar Feed 1.0//EN");
    appendLine(calendar, "VERSION:2.0");
    appendLine(calendar, "CALSCALE:GREGORIAN");
    for (RecipientCalendarEvent event : events) {
      appendEvent(calendar, disclosureMode, event);
    }
    appendLine(calendar, "END:VCALENDAR");
    byte[] encoded = calendar.toString().getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_DOCUMENT_BYTES) {
      throw DomainException.invalid(
          "CALENDAR_FEED_TOO_LARGE", "The calendar feed exceeds the safe response size.");
    }
    return encoded;
  }

  private void appendEvent(
      StringBuilder calendar, String disclosureMode, RecipientCalendarEvent event) {
    if (event == null
        || event.publicUid() == null
        || !event.publicUid().matches("pm-feed-v1-[A-Za-z0-9_-]{43}@personal-memo[.]invalid")
        || event.sequence() < 0
        || event.updatedAt() == null) {
      throw invalidEvent();
    }
    requireRfcYear(event.updatedAt());
    appendLine(calendar, "BEGIN:VEVENT");
    appendLine(calendar, "UID:" + event.publicUid());
    appendLine(calendar, "DTSTAMP:" + utc(event.updatedAt()));
    appendLine(calendar, "SEQUENCE:" + event.sequence());
    appendSchedule(calendar, event);
    if ("CANCELLED".equals(event.state())) {
      appendLine(calendar, "STATUS:CANCELLED");
    } else if ("ACTIVE".equals(event.state())) {
      String summary = "BUSY_ONLY".equals(disclosureMode) ? "Busy" : event.title();
      appendLine(calendar, "SUMMARY:" + escapeText(summary));
    } else {
      throw invalidEvent();
    }
    appendLine(calendar, "END:VEVENT");
  }

  private void appendSchedule(StringBuilder calendar, RecipientCalendarEvent event) {
    switch (event.scheduleKind()) {
      case "TIMED" -> {
        require(
            event.startAt() != null
                && event.startDate() == null
                && event.endDateExclusive() == null);
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
      case "ALL_DAY" -> {
        require(event.startDate() != null && event.startAt() == null && event.endAt() == null);
        requireRfcYear(event.startDate());
        requireRfcYear(event.endDateExclusive());
        require(
            event.endDateExclusive() == null
                || event.endDateExclusive().isAfter(event.startDate()));
        appendLine(calendar, "DTSTART;VALUE=DATE:" + DATE.format(event.startDate()));
        if (event.endDateExclusive() != null) {
          appendLine(calendar, "DTEND;VALUE=DATE:" + DATE.format(event.endDateExclusive()));
        }
      }
      default -> throw invalidEvent();
    }
  }

  private String utc(Instant value) {
    return UTC_DATE_TIME.format(value.truncatedTo(ChronoUnit.SECONDS));
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
      } else if (Character.isLowSurrogate(current)
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
    if (value != null) {
      requireRfcYear(value.atZone(ZoneOffset.UTC).getYear());
    }
  }

  private void requireRfcYear(LocalDate value) {
    if (value != null) {
      requireRfcYear(value.getYear());
    }
  }

  private void requireRfcYear(int year) {
    require(year >= 1 && year <= 9999);
  }

  private void require(boolean valid) {
    if (!valid) {
      throw invalidEvent();
    }
  }

  private DomainException invalidEvent() {
    return DomainException.invalid(
        "INVALID_CALENDAR_FEED_EVENT", "The calendar feed contains an invalid event.");
  }

  private DomainException unsafeText() {
    return DomainException.invalid(
        "CALENDAR_FEED_UNSAFE_TEXT", "The calendar feed contains unsafe text.");
  }
}
