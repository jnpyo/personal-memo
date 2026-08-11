package local.personalmemo.search.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SearchDtos {
  private SearchDtos() {}

  public record Request(
      String query,
      String lifecycleStatus,
      String taskState,
      Boolean overdue,
      Instant revisedFrom,
      Instant revisedBefore,
      Integer limit,
      String cursor) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
      throw new IllegalArgumentException("Unknown memo search request field.");
    }
  }

  public record CanonicalTag(UUID id, String name) {}

  public record Item(
      UUID memoId,
      int currentRevision,
      Integer canonicalRevision,
      String title,
      String preview,
      String lifecycleStatus,
      List<CanonicalTag> canonicalTags,
      String taskState,
      boolean overdue,
      boolean pinned,
      Instant revisedAt,
      List<String> matchedFields) {
    public Item {
      canonicalTags = List.copyOf(canonicalTags);
      matchedFields = List.copyOf(matchedFields);
    }
  }

  public record Page(List<Item> items, String nextCursor, boolean truncated) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
