package local.personalmemo.calendar.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import local.personalmemo.event.api.EventDtos;

public final class CalendarFeedDtos {
  private CalendarFeedDtos() {}

  public record Capabilities(String mode, String publicOrigin, String consentPolicyVersion) {}

  public record EligibleEvents(List<EventDtos.View> items, boolean truncated) {
    public EligibleEvents {
      items = List.copyOf(items);
    }
  }

  public record Create(
      @NotBlank @Size(max = 80) String displayName,
      @NotBlank String disclosureMode,
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> eventIds,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String bearerSecret) {
    public Create {
      eventIds = defensiveCopyAllowingNullElements(eventIds);
    }

    @Override
    public List<UUID> eventIds() {
      return defensiveCopyAllowingNullElements(eventIds);
    }
  }

  public record Update(
      @NotBlank @Size(max = 80) String displayName,
      @NotBlank String disclosureMode,
      @Positive long expectedVersion) {}

  public record Rotate(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String bearerSecret,
      @Positive long expectedVersion) {}

  public record EnableExternalPublication(
      @Positive long expectedVersion,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String bearerSecret,
      @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}") String consentPolicyVersion) {}

  public record Versioned(@Positive long expectedVersion) {}

  public record AddEvent(@NotNull UUID eventId, @Positive long expectedVersion) {}

  public record FeedSummary(
      UUID id,
      String displayName,
      String disclosureMode,
      String status,
      String publicationScope,
      String publicConsentPolicyVersion,
      Instant publicConsentGrantedAt,
      long version,
      int eventCount,
      Instant createdAt,
      Instant updatedAt,
      Instant rotatedAt,
      Instant revokedAt) {}

  public record FeedDetail(
      UUID id,
      String displayName,
      String disclosureMode,
      String status,
      String publicationScope,
      String publicConsentPolicyVersion,
      Instant publicConsentGrantedAt,
      long version,
      int eventCount,
      Instant createdAt,
      Instant updatedAt,
      Instant rotatedAt,
      Instant revokedAt,
      List<Entry> entries) {
    public FeedDetail {
      entries = List.copyOf(entries);
    }
  }

  public record Entry(
      UUID id,
      UUID eventId,
      String title,
      String scheduleKind,
      Instant startAt,
      Instant endAt,
      LocalDate startDate,
      LocalDate endDateExclusive,
      String sourceTimeZone,
      String state,
      int sequence) {}

  private static <T> List<T> defensiveCopyAllowingNullElements(List<T> values) {
    if (values == null) {
      return null;
    }
    return Collections.unmodifiableList(new ArrayList<>(values));
  }
}
