package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AnalysisProvenanceTest {
  @Test
  void acceptsFourBoundedServerOwnedVersions() {
    AnalysisProvenance provenance = new AnalysisProvenance("가".repeat(64), "none", "none", "none");

    assertThat(provenance.analyzerVersion()).hasSize(64);
    assertThatCode(() -> new AnalysisProvenance("fake-v2", "none", "none", "none"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingBlankOrOversizedVersions() {
    assertThatThrownBy(() -> new AnalysisProvenance(null, "none", "none", "none"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnalysisProvenance("fake-v2", " ", "none", "none"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnalysisProvenance("fake-v2", "none", "😀".repeat(65), "none"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
