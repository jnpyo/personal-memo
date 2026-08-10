package local.personalmemo.analysis.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public interface LocalAnalyzer {
  String proposalSchemaVersion();

  AnalysisProvenance provenance();

  default String version() {
    return provenance().analyzerVersion();
  }

  ObjectNode analyze(
      UUID memoId, int revision, String content, Instant baseInstant, String timeZone);
}
