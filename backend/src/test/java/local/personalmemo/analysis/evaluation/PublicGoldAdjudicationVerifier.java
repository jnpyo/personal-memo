package local.personalmemo.analysis.evaluation;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Verifies two external, raw-content-free review manifests against one immutable public v2 release.
 * The verifier compares verdicts only; it never resolves a disagreement or changes evaluation gold.
 */
final class PublicGoldAdjudicationVerifier {
  private static final String REVIEW_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-review.schema.json";
  private static final String PROTOCOL_VERSION = "public-v2-gold-review-v1";
  private static final List<String> VERDICT_FIELDS =
      List.of("dateMentionGold", "itemGold", "itemSourceSpanGold");

  private final ObjectMapper json;
  private final Schema reviewSchema;

  PublicGoldAdjudicationVerifier(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
    this.reviewSchema = loadReviewSchema();
  }

  ObjectNode verify(
      JsonNode regressionFixtures,
      JsonNode visibleChallengeFixtures,
      JsonNode firstReview,
      JsonNode secondReview) {
    PublicEvaluationRelease release =
        PublicEvaluationRelease.from(json, regressionFixtures, visibleChallengeFixtures);
    ReviewManifest first = parse(firstReview, release);
    ReviewManifest second = parse(secondReview, release);

    require(
        !first.reviewerToken().equalsIgnoreCase(second.reviewerToken()),
        "Reviewers must use distinct opaque tokens, compared case-insensitively.");
    require(first.releaseId().equals(second.releaseId()), "Review release IDs must match.");
    require(
        first.releaseDigestSha256().equals(second.releaseDigestSha256()),
        "Review release digests must match.");
    require(
        first.labelPolicyVersion().equals(second.labelPolicyVersion()),
        "Review label-policy versions must match.");
    require(
        first.protocolVersion().equals(second.protocolVersion()),
        "Review protocol versions must match.");

    int agreementCount = 0;
    int disagreementCount = 0;
    int acceptedByBothCount = 0;
    int changeRequiredByEitherCount = 0;
    for (String caseId : new TreeSet<>(release.caseIds())) {
      ReviewVerdicts firstVerdicts = first.caseReviews().get(caseId);
      ReviewVerdicts secondVerdicts = second.caseReviews().get(caseId);
      for (String field : VERDICT_FIELDS) {
        String left = firstVerdicts.value(field);
        String right = secondVerdicts.value(field);
        if (left.equals(right)) {
          agreementCount++;
        } else {
          disagreementCount++;
        }
        if ("ACCEPT".equals(left) && "ACCEPT".equals(right)) {
          acceptedByBothCount++;
        }
        if ("CHANGE_REQUIRED".equals(left) || "CHANGE_REQUIRED".equals(right)) {
          changeRequiredByEitherCount++;
        }
      }
    }

    int comparisonCount = Math.multiplyExact(release.caseIds().size(), VERDICT_FIELDS.size());
    String status =
        disagreementCount == 0 && changeRequiredByEitherCount == 0
            ? "CONSENSUS_ACCEPTED"
            : "NEEDS_HUMAN_RESOLUTION";
    return json.createObjectNode()
        .put("summaryVersion", "1")
        .put("status", status)
        .put("containsRawMemoContent", false)
        .put("reviewerCount", 2)
        .put("caseCount", release.caseIds().size())
        .put("fieldComparisonCount", comparisonCount)
        .put("agreementCount", agreementCount)
        .put("disagreementCount", disagreementCount)
        .put("acceptedByBothCount", acceptedByBothCount)
        .put("changeRequiredByEitherCount", changeRequiredByEitherCount)
        .put("automaticResolutionApplied", false);
  }

  private ReviewManifest parse(JsonNode review, PublicEvaluationRelease release) {
    try {
      if (!reviewSchema.validate(review).isEmpty()) {
        fail("A review manifest does not satisfy the strict review schema.");
      }
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "A review manifest could not be schema-validated.", exception);
    }

    require(
        release.digestSha256().equals(review.path("releaseDigestSha256").asText()),
        "A review manifest does not pin the exact public release digest.");
    require(
        PROTOCOL_VERSION.equals(review.path("protocolVersion").asText()),
        "A review manifest uses an unsupported protocol.");
    JsonNode attestations = review.path("attestations");
    for (String attestation :
        List.of(
            "humanReviewer",
            "independentReview",
            "analyzerOutputHidden",
            "otherReviewHidden",
            "publicSyntheticDataOnly")) {
      require(attestations.path(attestation).asBoolean(false), "A review attestation is missing.");
    }

    Map<String, ReviewVerdicts> caseReviews = new LinkedHashMap<>();
    for (JsonNode caseReview : review.path("caseReviews")) {
      String caseId = caseReview.path("caseId").asText();
      ReviewVerdicts previous =
          caseReviews.put(
              caseId,
              new ReviewVerdicts(
                  caseReview.path("dateMentionGold").asText(),
                  caseReview.path("itemGold").asText(),
                  caseReview.path("itemSourceSpanGold").asText()));
      require(previous == null, "A review manifest contains a duplicate case ID.");
    }
    require(
        caseReviews.keySet().equals(release.caseIds()),
        "A review manifest must cover the exact public case universe.");

    return new ReviewManifest(
        review.path("releaseId").asText(),
        review.path("releaseDigestSha256").asText(),
        review.path("labelPolicyVersion").asText(),
        review.path("protocolVersion").asText(),
        review.path("reviewerToken").asText(),
        Map.copyOf(caseReviews));
  }

  private Schema loadReviewSchema() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).failFast(false).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
    try (InputStream input = getClass().getResourceAsStream(REVIEW_SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("The evaluation review schema is missing.");
      }
      Schema schema = registry.getSchema(input);
      schema.initializeValidators();
      return schema;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "The evaluation review schema could not be loaded.", exception);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      fail(message);
    }
  }

  private static void fail(String message) {
    throw new IllegalArgumentException(message);
  }

  private record ReviewManifest(
      String releaseId,
      String releaseDigestSha256,
      String labelPolicyVersion,
      String protocolVersion,
      String reviewerToken,
      Map<String, ReviewVerdicts> caseReviews) {}

  private record ReviewVerdicts(
      String dateMentionGold, String itemGold, String itemSourceSpanGold) {
    String value(String field) {
      return switch (field) {
        case "dateMentionGold" -> dateMentionGold;
        case "itemGold" -> itemGold;
        case "itemSourceSpanGold" -> itemSourceSpanGold;
        default -> throw new IllegalArgumentException("Unknown review field.");
      };
    }
  }
}

record PublicEvaluationRelease(String digestSha256, Set<String> caseIds) {
  private static final String CASE_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-case.schema.json";

  PublicEvaluationRelease {
    caseIds = Set.copyOf(caseIds);
  }

  static PublicEvaluationRelease from(
      ObjectMapper json, JsonNode regressionFixtures, JsonNode visibleChallengeFixtures) {
    Objects.requireNonNull(json, "json");
    Schema caseSchema = loadCaseSchema();
    Set<String> caseIds = new HashSet<>();
    validateSplit(regressionFixtures, "REGRESSION", caseIds, caseSchema);
    validateSplit(visibleChallengeFixtures, "VISIBLE_CHALLENGE", caseIds, caseSchema);

    ObjectNode release =
        json.createObjectNode()
            .put("datasetVersion", "2")
            .set("regression", regressionFixtures.deepCopy());
    release.set("visibleChallenge", visibleChallengeFixtures.deepCopy());
    return new PublicEvaluationRelease(sha256(json, release), caseIds);
  }

  private static void validateSplit(
      JsonNode fixtures, String split, Set<String> caseIds, Schema caseSchema) {
    require(
        fixtures != null && fixtures.isArray() && !fixtures.isEmpty(), "A public split is empty.");
    for (JsonNode fixture : fixtures) {
      require(fixture.isObject(), "A public evaluation case is invalid.");
      try {
        require(
            caseSchema.validate(fixture).isEmpty(),
            "A public evaluation case violates the strict version-2 schema.");
        EvaluationV2GoldIntegrity.validate(fixture);
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException(
            "A public evaluation case has invalid version-2 gold.", exception);
      }
      require("2".equals(fixture.path("datasetVersion").asText()), "Dataset version must be 2.");
      require(split.equals(fixture.path("split").asText()), "A public case has the wrong split.");
      require(!fixture.has("sourcePolicy"), "Blind provenance is not allowed in a public release.");
      String caseId = fixture.path("id").asText();
      require(!caseId.isBlank() && caseIds.add(caseId), "Public case IDs must be unique.");
    }
  }

  private static Schema loadCaseSchema() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).failFast(false).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
    try (InputStream input =
        PublicEvaluationRelease.class.getResourceAsStream(CASE_SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("The version-2 evaluation case schema is missing.");
      }
      Schema schema = registry.getSchema(input);
      schema.initializeValidators();
      return schema;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "The version-2 evaluation case schema could not be loaded.", exception);
    }
  }

  private static String sha256(ObjectMapper json, JsonNode value) {
    StringBuilder canonical = new StringBuilder();
    appendCanonical(json, value, canonical);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  private static void appendCanonical(ObjectMapper json, JsonNode value, StringBuilder output) {
    if (value.isObject()) {
      output.append('{');
      List<String> names = new ArrayList<>();
      names.addAll(value.propertyNames());
      names.sort(String::compareTo);
      for (int index = 0; index < names.size(); index++) {
        if (index > 0) {
          output.append(',');
        }
        String name = names.get(index);
        appendQuoted(json, name, output);
        output.append(':');
        appendCanonical(json, value.path(name), output);
      }
      output.append('}');
      return;
    }
    if (value.isArray()) {
      output.append('[');
      for (int index = 0; index < value.size(); index++) {
        if (index > 0) {
          output.append(',');
        }
        appendCanonical(json, value.get(index), output);
      }
      output.append(']');
      return;
    }
    if (value.isTextual()) {
      appendQuoted(json, value.asText(), output);
      return;
    }
    output.append(value.toString());
  }

  private static void appendQuoted(ObjectMapper json, String value, StringBuilder output) {
    try {
      output.append(json.writeValueAsString(value));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "Public evaluation JSON could not be canonicalized.", exception);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
