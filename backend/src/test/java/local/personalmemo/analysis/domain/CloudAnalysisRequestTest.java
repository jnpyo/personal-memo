package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class CloudAnalysisRequestTest {
  private static final CloudGatewayDescriptor NO_NETWORK =
      new CloudGatewayDescriptor(
          "fake-cloud-v2", "fake", "none", "no-network-v1", CloudTransferMode.NO_NETWORK);
  private static final CloudGatewayDescriptor EXTERNAL =
      new CloudGatewayDescriptor(
          "gateway-v1",
          "provider-v1",
          "model-v1",
          "memo-transfer-v1",
          CloudTransferMode.EXTERNAL_MEMO_CONTENT);
  private static final CloudProviderRequestToken TOKEN =
      CloudProviderRequestToken.issue(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          "ANALYSIS_START",
          "analysis-key",
          "a".repeat(64));
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void defensivelyCopiesTheProposalAndRoutingReasons() {
    ObjectNode proposal = json.createObjectNode().put("memoRevision", 1);
    List<AmbiguityReason> reasons = new ArrayList<>();
    reasons.add(AmbiguityReason.LOW_TYPE_MARGIN);

    CloudAnalysisRequest request =
        new CloudAnalysisRequest(
            proposal,
            reasons,
            "field-policy-v1",
            NO_NETWORK,
            Optional.empty(),
            Optional.empty(),
            TOKEN);
    proposal.put("memoRevision", 2);
    reasons.clear();
    request.validatedLocalProposal().put("memoRevision", 3);

    assertThat(request.validatedLocalProposal().path("memoRevision").asInt()).isEqualTo(1);
    assertThat(request.routingReasons()).containsExactly(AmbiguityReason.LOW_TYPE_MARGIN);
  }

  @Test
  void rejectsAnUnversionedRoutingPolicy() {
    ObjectNode proposal = json.createObjectNode();

    assertThatThrownBy(
            () ->
                new CloudAnalysisRequest(
                    proposal,
                    List.of(),
                    " ",
                    NO_NETWORK,
                    Optional.empty(),
                    Optional.empty(),
                    TOKEN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundsRoutingPolicyVersionByUnicodeCodePoint() {
    ObjectNode proposal = json.createObjectNode();

    assertThatCode(
            () ->
                new CloudAnalysisRequest(
                    proposal,
                    List.of(),
                    "😀".repeat(AnalysisProvenance.MAX_VERSION_LENGTH),
                    NO_NETWORK,
                    Optional.empty(),
                    Optional.empty(),
                    TOKEN))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                new CloudAnalysisRequest(
                    proposal,
                    List.of(),
                    "😀".repeat(AnalysisProvenance.MAX_VERSION_LENGTH + 1),
                    NO_NETWORK,
                    Optional.empty(),
                    Optional.empty(),
                    TOKEN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresACoherentExternalConsentSnapshot() {
    ObjectNode proposal = json.createObjectNode();
    Instant checkedAt = Instant.parse("2026-08-10T02:00:00Z");
    Instant grantedAt = Instant.parse("2026-08-10T01:00:00Z");

    CloudAnalysisRequest request =
        new CloudAnalysisRequest(
            proposal,
            List.of(),
            "field-policy-v1",
            EXTERNAL,
            Optional.of(checkedAt),
            Optional.of(grantedAt),
            TOKEN);

    assertThat(request.descriptor()).isEqualTo(EXTERNAL);
    assertThat(request.authorizationCheckedAt()).contains(checkedAt);
    assertThat(request.acceptedConsentGrantedAt()).contains(grantedAt);
    assertThatThrownBy(
            () ->
                new CloudAnalysisRequest(
                    proposal,
                    List.of(),
                    "field-policy-v1",
                    EXTERNAL,
                    Optional.of(checkedAt),
                    Optional.empty(),
                    TOKEN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CloudAnalysisRequest(
                    proposal,
                    List.of(),
                    "field-policy-v1",
                    EXTERNAL,
                    Optional.of(checkedAt),
                    Optional.of(checkedAt.plusSeconds(1)),
                    TOKEN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void redactsTheProposalAndProviderRequestTokenFromStringForm() {
    ObjectNode proposal = json.createObjectNode().put("rawMemo", "private memo body");
    CloudAnalysisRequest request =
        new CloudAnalysisRequest(
            proposal,
            List.of(),
            "field-policy-v1",
            NO_NETWORK,
            Optional.empty(),
            Optional.empty(),
            TOKEN);

    assertThat(request.toString())
        .doesNotContain("private memo body")
        .doesNotContain(TOKEN.value())
        .contains("proposal=redacted", "providerRequestToken=redacted");
  }
}
