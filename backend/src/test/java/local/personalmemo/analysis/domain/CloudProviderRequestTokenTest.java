package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CloudProviderRequestTokenTest {
  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void issuesAStableOpaqueTokenForTheSameLogicalRequest() {
    CloudProviderRequestToken first =
        CloudProviderRequestToken.issue(OWNER, "ANALYSIS_START", "분석-key-1", "a".repeat(64));
    CloudProviderRequestToken replay =
        CloudProviderRequestToken.issue(OWNER, "ANALYSIS_START", "분석-key-1", "a".repeat(64));

    assertThat(replay).isEqualTo(first);
    assertThat(first.value())
        .isEqualTo("pmr1_53f768faa7f33d05372ca0f728dee98d87243fc01584eec9fd3089c721e45460");
    assertThat(first.toString()).doesNotContain(first.value()).contains("redacted");
  }

  @Test
  void separatesOwnerKeyAndRequestIdentity() {
    CloudProviderRequestToken baseline =
        CloudProviderRequestToken.issue(OWNER, "ANALYSIS_START", "analysis-key", "a".repeat(64));

    assertThat(
            CloudProviderRequestToken.issue(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "ANALYSIS_START",
                "analysis-key",
                "a".repeat(64)))
        .isNotEqualTo(baseline);
    assertThat(
            CloudProviderRequestToken.issue(OWNER, "ANALYSIS_START", "another-key", "a".repeat(64)))
        .isNotEqualTo(baseline);
    assertThat(
            CloudProviderRequestToken.issue(
                OWNER, "ANALYSIS_RETRY", "analysis-key", "a".repeat(64)))
        .isNotEqualTo(baseline);
    assertThat(
            CloudProviderRequestToken.issue(
                OWNER, "ANALYSIS_START", "analysis-key", "b".repeat(64)))
        .isNotEqualTo(baseline);
  }

  @Test
  void rejectsMalformedStoredValuesAndRequestHashes() {
    assertThatThrownBy(() -> new CloudProviderRequestToken("pmr1_not-a-token"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CloudProviderRequestToken.issue(
                    OWNER, "ANALYSIS_START", "analysis-key", "not-a-hash"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
