package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class PublicGoldAdjudicationVerifierTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
  private static final String REVIEW_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-review.schema.json";

  private final ObjectMapper json = new ObjectMapper();
  private final PublicGoldAdjudicationVerifier verifier = new PublicGoldAdjudicationVerifier(json);

  @Test
  void returnsOnlyAggregateCountsForTwoCompleteIndependentReviews() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    String digest = PublicEvaluationRelease.from(json, regression, challenge).digestSha256();
    ObjectNode first = review("Reviewer-AA", digest, regression, challenge);
    ObjectNode second = review("Reviewer-BB", digest, regression, challenge);

    ObjectNode summary = verifier.verify(regression, challenge, first, second);

    assertThat(summary.path("status").asText()).isEqualTo("CONSENSUS_ACCEPTED");
    assertThat(summary.path("reviewerCount").asInt()).isEqualTo(2);
    assertThat(summary.path("caseCount").asInt()).isEqualTo(24);
    assertThat(summary.path("fieldComparisonCount").asInt()).isEqualTo(72);
    assertThat(summary.path("agreementCount").asInt()).isEqualTo(72);
    assertThat(summary.path("disagreementCount").asInt()).isZero();
    assertThat(summary.path("acceptedByBothCount").asInt()).isEqualTo(72);
    assertThat(summary.path("changeRequiredByEitherCount").asInt()).isZero();
    assertThat(summary.path("automaticResolutionApplied").asBoolean()).isFalse();
    assertThat(summary.path("containsRawMemoContent").asBoolean()).isFalse();
    assertThat(fieldNames(summary))
        .containsExactlyInAnyOrder(
            "summaryVersion",
            "status",
            "containsRawMemoContent",
            "reviewerCount",
            "caseCount",
            "fieldComparisonCount",
            "agreementCount",
            "disagreementCount",
            "acceptedByBothCount",
            "changeRequiredByEitherCount",
            "automaticResolutionApplied");

    String serialized = json.writeValueAsString(summary);
    assertThat(serialized)
        .doesNotContain(
            first.path("reviewerToken").asText(),
            second.path("reviewerToken").asText(),
            digest,
            first.path("releaseId").asText(),
            first.path("labelPolicyVersion").asText());
    for (JsonNode fixture : regression) {
      assertThat(serialized)
          .doesNotContain(fixture.path("id").asText(), fixture.path("content").asText());
    }
    for (JsonNode fixture : challenge) {
      assertThat(serialized)
          .doesNotContain(fixture.path("id").asText(), fixture.path("content").asText());
    }
  }

  @Test
  void rejectsReviewerTokensThatDifferOnlyByCase() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    String digest = PublicEvaluationRelease.from(json, regression, challenge).digestSha256();

    assertThatThrownBy(
            () ->
                verifier.verify(
                    regression,
                    challenge,
                    review("Reviewer-AA", digest, regression, challenge),
                    review("reviewer-aa", digest, regression, challenge)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("case-insensitively");
  }

  @Test
  void rejectsDigestPolicyProtocolAndCaseUniverseDrift() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    String digest = PublicEvaluationRelease.from(json, regression, challenge).digestSha256();
    ObjectNode first = review("Reviewer-AA", digest, regression, challenge);
    ObjectNode second = review("Reviewer-BB", digest, regression, challenge);

    ObjectNode wrongDigest = second.deepCopy();
    wrongDigest.put("releaseDigestSha256", "0".repeat(64));
    assertThatThrownBy(() -> verifier.verify(regression, challenge, first, wrongDigest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact public release digest");

    ObjectNode wrongPolicy = second.deepCopy();
    wrongPolicy.put("labelPolicyVersion", "different-policy-v1");
    assertThatThrownBy(() -> verifier.verify(regression, challenge, first, wrongPolicy))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("label-policy versions");

    ObjectNode wrongProtocol = second.deepCopy();
    wrongProtocol.put("protocolVersion", "unsupported-protocol-v1");
    assertThatThrownBy(() -> verifier.verify(regression, challenge, first, wrongProtocol))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("could not be schema-validated");

    ObjectNode incomplete = second.deepCopy();
    ((ArrayNode) incomplete.path("caseReviews")).remove(0);
    assertThatThrownBy(() -> verifier.verify(regression, challenge, first, incomplete))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact public case universe");
  }

  @Test
  void rejectsRawOrCommentFieldsAndNeverAutomaticallyResolvesVerdicts() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    String digest = PublicEvaluationRelease.from(json, regression, challenge).digestSha256();
    ObjectNode first = review("Reviewer-AA", digest, regression, challenge);
    ObjectNode second = review("Reviewer-BB", digest, regression, challenge);

    ObjectNode withComment = second.deepCopy();
    ((ObjectNode) withComment.path("caseReviews").get(0)).put("comment", "not allowed");
    assertThatThrownBy(() -> verifier.verify(regression, challenge, first, withComment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("could not be schema-validated");

    ObjectNode disagreement = second.deepCopy();
    ((ObjectNode) disagreement.path("caseReviews").get(0))
        .put("dateMentionGold", "CHANGE_REQUIRED");
    ObjectNode summary = verifier.verify(regression, challenge, first, disagreement);
    assertThat(summary.path("status").asText()).isEqualTo("NEEDS_HUMAN_RESOLUTION");
    assertThat(summary.path("disagreementCount").asInt()).isEqualTo(1);
    assertThat(summary.path("changeRequiredByEitherCount").asInt()).isEqualTo(1);
    assertThat(summary.path("automaticResolutionApplied").asBoolean()).isFalse();

    ObjectNode bothRequireChange = first.deepCopy();
    ((ObjectNode) bothRequireChange.path("caseReviews").get(0))
        .put("dateMentionGold", "CHANGE_REQUIRED");
    ObjectNode unresolved = verifier.verify(regression, challenge, bothRequireChange, disagreement);
    assertThat(unresolved.path("disagreementCount").asInt()).isZero();
    assertThat(unresolved.path("status").asText()).isEqualTo("NEEDS_HUMAN_RESOLUTION");
    assertThat(unresolved.path("automaticResolutionApplied").asBoolean()).isFalse();
  }

  @Test
  void cannotUseTheVersionTwoReviewProtocolToClaimVersionThreeBindingAdjudication()
      throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    String digest = PublicEvaluationRelease.from(json, regression, challenge).digestSha256();
    ObjectNode first = review("Reviewer-AA", digest, regression, challenge);
    ObjectNode bindingClaim = review("Reviewer-BB", digest, regression, challenge);
    bindingClaim.put("reviewKind", "PUBLIC_V3_TASK_DUE_BINDING");
    ((ArrayNode) bindingClaim.path("reviewScope")).add("TASK_DUE_BINDING_GOLD");

    assertThatThrownBy(() -> verifier.verify(regression, challenge, first, bindingClaim))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("could not be schema-validated");
  }

  @Test
  void rejectsMalformedOrDanglingVersionTwoGoldBeforeReviewAcceptance() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    ArrayNode dangling = (ArrayNode) regression.deepCopy();
    ((ArrayNode) dangling.get(0).path("expectedDates").path("emittedCandidateGoldIds"))
        .set(0, json.getNodeFactory().textNode("missing-date"));
    assertThatThrownBy(() -> PublicEvaluationRelease.from(json, dangling, challenge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid version-2 gold");

    ArrayNode unexpectedField = (ArrayNode) regression.deepCopy();
    ((ObjectNode) unexpectedField.get(0)).put("unexpectedRawField", "not allowed");
    assertThatThrownBy(() -> PublicEvaluationRelease.from(json, unexpectedField, challenge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid version-2 gold");
  }

  @Test
  void reviewContractResourceMatchesRepositoryCopy() throws Exception {
    JsonNode resource = readResource(REVIEW_SCHEMA_RESOURCE);
    JsonNode repository =
        json.readTree(
            Files.readString(
                repositoryPath("contracts/korean-memo-evaluation-review.schema.json"),
                StandardCharsets.UTF_8));
    assertThat(resource).isEqualTo(repository);
  }

  private ObjectNode review(
      String reviewerToken, String digest, JsonNode regression, JsonNode challenge) {
    ObjectNode review =
        json.createObjectNode()
            .put("reviewSchemaVersion", "1")
            .put("reviewKind", "PUBLIC_V2_DATE_ITEM_GOLD")
            .put("datasetVersion", "2")
            .put("releaseId", "public-v2-test-release")
            .put("releaseDigestSha256", digest)
            .put("labelPolicyVersion", "v2-date-item-gold-test-policy")
            .put("protocolVersion", "public-v2-gold-review-v1")
            .put("reviewerToken", reviewerToken);
    review
        .putArray("reviewScope")
        .add("DATE_MENTION_GOLD")
        .add("ITEM_GOLD")
        .add("ITEM_SOURCE_SPAN_GOLD");
    review
        .putObject("attestations")
        .put("humanReviewer", true)
        .put("independentReview", true)
        .put("analyzerOutputHidden", true)
        .put("otherReviewHidden", true)
        .put("publicSyntheticDataOnly", true);
    ArrayNode cases = review.putArray("caseReviews");
    for (JsonNode fixtures : Set.of(regression, challenge)) {
      for (JsonNode fixture : fixtures) {
        cases
            .addObject()
            .put("caseId", fixture.path("id").asText())
            .put("dateMentionGold", "ACCEPT")
            .put("itemGold", "ACCEPT")
            .put("itemSourceSpanGold", "ACCEPT");
      }
    }
    return review;
  }

  private Set<String> fieldNames(JsonNode value) {
    Set<String> fields = new HashSet<>();
    fields.addAll(value.propertyNames());
    return fields;
  }

  private JsonNode fixtures(String resource) throws Exception {
    JsonNode value = readResource(resource);
    assertThat(value.isArray()).isTrue();
    return value;
  }

  private JsonNode readResource(String resource) throws Exception {
    try (InputStream input = getClass().getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Test resource is missing: " + resource);
      }
      return json.readTree(input);
    }
  }

  private Path repositoryPath(String relativePath) {
    Path fromBackend = Path.of("..", relativePath);
    if (Files.exists(fromBackend)) {
      return fromBackend;
    }
    Path fromRoot = Path.of(relativePath);
    if (Files.exists(fromRoot)) {
      return fromRoot;
    }
    throw new IllegalStateException("Repository file is missing: " + relativePath);
  }
}
