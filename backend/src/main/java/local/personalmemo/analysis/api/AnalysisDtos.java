package local.personalmemo.analysis.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class AnalysisDtos {
  private AnalysisDtos() {}

  public record Start(
      @Min(1) int memoRevision,
      @NotBlank @Pattern(regexp = "AUTO", message = "must be AUTO in the fake-analysis flow") String policy) {}

  public record RunView(UUID id, UUID memoId, int memoRevision, String status, UUID proposalId) {}

  public record Due(
      @NotBlank @Size(max = 100) String surfaceText,
      @Size(max = 100) String value,
      @NotBlank String precision,
      @NotBlank @Size(max = 64) String timeZone,
      boolean timeSpecified) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unknown analysis due field.");
    }
  }

  public record EventSchedule(
      @NotBlank String mode,
      @NotBlank @Size(max = 100) String start,
      @Size(max = 100) String end,
      @NotBlank @Size(max = 64) String timeZone) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unknown analysis event schedule field.");
    }
  }

  public record Item(
      String proposalCandidateId,
      @NotBlank String kind,
      @NotBlank @Size(max = 200) String title,
      @Valid Due due,
      @Valid EventSchedule eventSchedule) {
    public Item(String proposalCandidateId, String kind, String title, Due due) {
      this(proposalCandidateId, kind, title, due, null);
    }

    public Item(String kind, String title, Due due) {
      this(null, kind, title, due, null);
    }

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unknown analysis item field.");
    }
  }

  public record SelectedRelation(Integer proposalIndex) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unknown selected relation field.");
    }
  }

  // TagNormalizer owns Unicode-scalar and normalized-length validation. Bean Validation's @Size
  // counts UTF-16 code units and would reject an otherwise valid 100-supplementary-code-point name.
  public record Tag(UUID existingTagId, String newCanonicalName) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unknown analysis tag field.");
    }
  }

  public record Apply(
      @Min(1) int expectedMemoRevision,
      @NotBlank String selectedType,
      @NotBlank @Size(max = 200) String title,
      @NotNull @Size(max = 10) List<@NotNull @Valid Tag> selectedTags,
      @NotEmpty @Size(max = 3) List<@NotNull @Valid Item> items,
      @Size(max = 10) List<@NotNull @Valid SelectedRelation> selectedRelations,
      String selectionSchemaVersion) {
    public Apply {
      selectedTags = defensiveCopyAllowingNullElements(selectedTags);
      items = defensiveCopyAllowingNullElements(items);
      selectedRelations = defensiveCopyAllowingNullElements(selectedRelations);
    }

    public Apply(
        int expectedMemoRevision,
        String selectedType,
        String title,
        List<Tag> selectedTags,
        List<Item> items) {
      this(expectedMemoRevision, selectedType, title, selectedTags, items, null, null);
    }

    public Apply(
        int expectedMemoRevision,
        String selectedType,
        String title,
        List<Tag> selectedTags,
        List<Item> items,
        List<SelectedRelation> selectedRelations) {
      this(expectedMemoRevision, selectedType, title, selectedTags, items, selectedRelations, null);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    private Apply(Input input) {
      this(
          input.expectedMemoRevision,
          input.selectedType,
          input.title,
          input.selectedTags,
          input.items,
          input.selectedRelations,
          input.selectionSchemaVersion);
    }

    private static <T> List<T> defensiveCopyAllowingNullElements(List<T> values) {
      if (values == null) {
        return null;
      }
      // Bean Validation owns the element-level null error and reports a bounded 422. Keep the
      // snapshot unmodifiable without List.copyOf, which rejects null before validation runs.
      return Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override
    public List<Tag> selectedTags() {
      return defensiveCopyAllowingNullElements(selectedTags);
    }

    @Override
    public List<Item> items() {
      return defensiveCopyAllowingNullElements(items);
    }

    @Override
    public List<SelectedRelation> selectedRelations() {
      return defensiveCopyAllowingNullElements(selectedRelations);
    }

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unknown analysis apply field.");
    }

    private static final class Input {
      private int expectedMemoRevision;
      private String selectedType;
      private String title;
      private List<Tag> selectedTags;
      private List<Item> items;
      private List<SelectedRelation> selectedRelations;
      private String selectionSchemaVersion;

      public void setExpectedMemoRevision(int expectedMemoRevision) {
        this.expectedMemoRevision = expectedMemoRevision;
      }

      public void setSelectedType(String selectedType) {
        this.selectedType = selectedType;
      }

      public void setTitle(String title) {
        this.title = title;
      }

      public void setSelectedTags(List<Tag> selectedTags) {
        this.selectedTags = defensiveCopyAllowingNullElements(selectedTags);
      }

      public void setItems(List<Item> items) {
        this.items = defensiveCopyAllowingNullElements(items);
      }

      @JsonSetter(nulls = Nulls.FAIL)
      public void setSelectedRelations(List<SelectedRelation> selectedRelations) {
        this.selectedRelations = defensiveCopyAllowingNullElements(selectedRelations);
      }

      public void setSelectionSchemaVersion(String selectionSchemaVersion) {
        this.selectionSchemaVersion = selectionSchemaVersion;
      }

      @JsonAnySetter
      public void rejectUnknownField(String ignoredName, Object ignoredValue) {
        throw new IllegalArgumentException("Unknown analysis apply field.");
      }
    }
  }

  public record ApplicationView(UUID applicationId, String status) {}

  public record ApplicationRecoveryView(UUID applicationId, String status) {}

  public record ProposalRecoveryView(
      UUID proposalId, String status, Instant createdAt, JsonNode proposal) {}

  public record ReviewDispositionView(UUID proposalId, String status) {}

  public record RelationReviewCandidate(
      int proposalIndex, String targetType, UUID targetId, String targetLabel, boolean available) {}
}
