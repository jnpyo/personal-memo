package local.personalmemo.calendar.application;

import java.time.Instant;
import java.time.LocalDate;

record RecipientCalendarEvent(
    String publicUid,
    String title,
    String state,
    int sequence,
    String scheduleKind,
    Instant startAt,
    Instant endAt,
    LocalDate startDate,
    LocalDate endDateExclusive,
    String sourceTimeZone,
    Instant updatedAt) {}
