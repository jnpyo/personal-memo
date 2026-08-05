package local.personalmemo.memo.domain;

import java.time.Instant;
import java.util.UUID;

public record MemoSnapshot(
    UUID id,
    int currentRevision,
    String content,
    String status,
    Instant createdAt,
    String analysisState) {
  public boolean isActive() {
    return "ACTIVE".equals(status);
  }
}
