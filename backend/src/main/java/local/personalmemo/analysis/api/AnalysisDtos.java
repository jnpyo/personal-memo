package local.personalmemo.analysis.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class AnalysisDtos {
  private AnalysisDtos() {}

  public record Start(
      @Min(1) int memoRevision,
      @NotBlank @Pattern(regexp = "AUTO", message = "must be AUTO in the fake-analysis flow")
          String policy) {}

  public record RunView(
      UUID id, UUID memoId, int memoRevision, String status, UUID proposalId) {}

  public record Due(
      @NotBlank @Size(max = 100) String surfaceText,
      @Size(max = 100) String value,
      @NotBlank String precision,
      @NotBlank @Size(max = 64) String timeZone,
      boolean timeSpecified) {}

  public record Item(
      @NotBlank String kind,
      @NotBlank @Size(max = 200) String title,
      @Valid Due due) {}

  public record Tag(UUID existingTagId, @Size(max = 100) String newCanonicalName) {}

  public record Apply(
      @Min(1) int expectedMemoRevision,
      @NotBlank String selectedType,
      @NotBlank @Size(max = 200) String title,
      @NotNull @Size(max = 10) List<@Valid Tag> selectedTags,
      @NotEmpty @Size(max = 3) List<@Valid Item> items) {}

  public record ApplicationView(UUID applicationId, String status) {}

  public record ApplicationRecoveryView(UUID applicationId, String status) {}

  public record ProposalRecoveryView(
      UUID proposalId, String status, Instant createdAt, JsonNode proposal) {}

  public record ReviewDispositionView(UUID proposalId, String status) {}
}
