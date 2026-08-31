package local.personalmemo.event.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class EventDtos {
  private EventDtos() {}

  public record View(
      UUID id,
      String title,
      String scheduleKind,
      Instant startAt,
      Instant endAt,
      LocalDate startDate,
      LocalDate endDateExclusive,
      String sourceTimeZone) {}
}
