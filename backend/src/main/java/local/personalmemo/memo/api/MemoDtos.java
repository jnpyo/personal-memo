package local.personalmemo.memo.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class MemoDtos {
  private MemoDtos() {}

  public record Create(
      @NotNull UUID id,
      @NotBlank @Size(max = 20_000) String content,
      @NotNull OffsetDateTime clientCreatedAt,
      @NotBlank @Size(max = 64) String timeZone) {}

  public record Update(
      @Min(1) int expectedRevision,
      @NotBlank @Size(max = 20_000) String content,
      OffsetDateTime clientUpdatedAt,
      @Size(max = 64) String timeZone) {}

  public record View(
      UUID id,
      int currentRevision,
      String content,
      String status,
      String analysisState,
      Instant createdAt) {}
}
