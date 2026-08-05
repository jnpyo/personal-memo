package local.personalmemo.analysis.domain;

/** Server-owned versions needed to reproduce an analysis decision. */
public record AnalysisProvenance(
    String analyzerVersion,
    String promptVersion,
    String localModelVersion,
    String embeddingModelVersion) {
  public static final int MAX_VERSION_LENGTH = 64;

  public AnalysisProvenance {
    analyzerVersion = requireVersion(analyzerVersion, "analyzerVersion");
    promptVersion = requireVersion(promptVersion, "promptVersion");
    localModelVersion = requireVersion(localModelVersion, "localModelVersion");
    embeddingModelVersion = requireVersion(embeddingModelVersion, "embeddingModelVersion");
  }

  private static String requireVersion(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > MAX_VERSION_LENGTH) {
      throw new IllegalArgumentException(
          field + " must contain between 1 and " + MAX_VERSION_LENGTH + " characters.");
    }
    return value;
  }
}
