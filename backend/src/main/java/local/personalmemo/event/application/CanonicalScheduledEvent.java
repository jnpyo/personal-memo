package local.personalmemo.event.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CanonicalScheduledEvent(
    UUID id,
    String title,
    String scheduleKind,
    Instant startAt,
    Instant endAt,
    LocalDate startDate,
    LocalDate endDateExclusive,
    String sourceTimeZone,
    Instant createdAt) {}
