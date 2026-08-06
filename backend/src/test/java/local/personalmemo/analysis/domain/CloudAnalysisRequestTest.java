package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class CloudAnalysisRequestTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void defensivelyCopiesTheProposalAndRoutingReasons() {
    ObjectNode proposal = json.createObjectNode().put("memoRevision", 1);
    List<AmbiguityReason> reasons = new ArrayList<>();
    reasons.add(AmbiguityReason.LOW_TYPE_MARGIN);

    CloudAnalysisRequest request = new CloudAnalysisRequest(proposal, reasons, "field-policy-v1");
    proposal.put("memoRevision", 2);
    reasons.clear();
    request.validatedLocalProposal().put("memoRevision", 3);

    assertThat(request.validatedLocalProposal().path("memoRevision").asInt()).isEqualTo(1);
    assertThat(request.routingReasons()).containsExactly(AmbiguityReason.LOW_TYPE_MARGIN);
  }

  @Test
  void rejectsAnUnversionedRoutingPolicy() {
    ObjectNode proposal = json.createObjectNode();

    assertThatThrownBy(() -> new CloudAnalysisRequest(proposal, List.of(), " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundsRoutingPolicyVersionByUnicodeCodePoint() {
    ObjectNode proposal = json.createObjectNode();

    assertThatCode(
            () ->
                new CloudAnalysisRequest(
                    proposal, List.of(), "😀".repeat(AnalysisProvenance.MAX_VERSION_LENGTH)))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                new CloudAnalysisRequest(
                    proposal, List.of(), "😀".repeat(AnalysisProvenance.MAX_VERSION_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
