package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CloudAnalysisResultTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void successDefensivelyCopiesTheProposal() {
    var proposal = json.createObjectNode().put("memoRevision", 1);
    var success = CloudAnalysisResult.success(proposal);

    proposal.put("memoRevision", 2);
    success.proposal().put("memoRevision", 3);

    assertThat(success.proposal().path("memoRevision").asInt()).isEqualTo(1);
  }

  @Test
  void failureContainsOnlyABoundedReasonWithoutProviderText() {
    var failure = CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT);

    assertThat(failure.reason()).isEqualTo(CloudAnalysisFailureReason.TIMEOUT);
    assertThat(failure.toString()).doesNotContain("provider message");
    assertThatThrownBy(() -> CloudAnalysisResult.failure(null))
        .isInstanceOf(NullPointerException.class);
  }
}
