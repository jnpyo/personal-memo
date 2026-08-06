package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class FakeAnalyzerTest {
  private final FakeAnalyzer analyzer = new FakeAnalyzer(new ObjectMapper());

  @Test
  void preservesDateSurfaceAndEmitsOwnerNeutralAliasCandidate() {
    var result =
        analyzer.analyze(
            UUID.randomUUID(),
            1,
            "11.25 OS과제 제출",
            Instant.parse("2026-08-05T02:00:00Z"),
            "Asia/Seoul");

    assertThat(result.at("/dateCandidates/0/surfaceText").asText()).isEqualTo("11.25");
    assertThat(result.at("/dateCandidates/0/value").asText()).isEqualTo("2026-11-25");
    assertThat(result.at("/dateCandidates/0/precision").asText()).isEqualTo("DATE_ONLY");
    assertThat(result.at("/dateCandidates/0/timeSpecified").asBoolean()).isFalse();
    assertThat(result.at("/dateCandidates/0/ambiguityReasons").toString())
        .contains("MISSING_YEAR", "MISSING_TIME");
    assertThat(result.at("/tagCandidates/0/canonicalName").asText()).isEqualTo("운영체제");
    assertThat(result.at("/tagCandidates/0/matchedAlias").asText()).isEqualTo("OS");
    assertThat(result.at("/tagCandidates/0/existingTagId").isNull()).isTrue();
    assertThat(result.at("/tagCandidates/0/isNewProposal").asBoolean()).isTrue();
    assertThat(result.path("ambiguityReasons").toString()).contains("NEW_TOPIC");
  }

  @Test
  void rollsDateOnlyCandidateIntoNextYearWhenMonthAndDayAlreadyPassed() {
    var result =
        analyzer.analyze(
            UUID.randomUUID(), 4, "1.2 과제 제출", Instant.parse("2026-12-31T15:30:00Z"), "Asia/Seoul");

    assertThat(result.at("/dateCandidates/0/value").asText()).isEqualTo("2027-01-02");
    assertThat(result.path("memoRevision").asInt()).isEqualTo(4);
  }

  @Test
  void doesNotCreateCanonicalRecordsAndCarriesVersionMetadataOnlyInProposal() {
    UUID memoId = UUID.randomUUID();
    var result =
        analyzer.analyze(memoId, 2, "아이디어 기록", Instant.parse("2026-08-05T02:00:00Z"), "Asia/Seoul");

    assertThat(result.path("schemaVersion").asText()).isEqualTo("1");
    assertThat(result.path("memoId").asText()).isEqualTo(memoId.toString());
    assertThat(analyzer.version()).isEqualTo("fake-v3");
    assertThat(analyzer.provenance().promptVersion()).isEqualTo("none");
    assertThat(analyzer.provenance().localModelVersion()).isEqualTo("none");
    assertThat(analyzer.provenance().embeddingModelVersion()).isEqualTo("none");
    assertThat(result.at("/providerMetadata/analyzerVersion").asText()).isEqualTo("fake-v3");
    assertThat(result.at("/providerMetadata/promptVersion").asText()).isEqualTo("none");
    assertThat(result.at("/providerMetadata/localModelVersion").asText()).isEqualTo("none");
    assertThat(result.at("/providerMetadata/embeddingModelVersion").asText()).isEqualTo("none");
    assertThat(result.at("/providerMetadata/toolCalls").asInt()).isZero();
    assertThat(result.at("/providerMetadata/routingPolicyVersion").asText())
        .isEqualTo("field-policy-v1");
    assertThat(result.path("relationCandidates").isArray()).isTrue();
    assertThat(result.path("itemCandidates").size()).isLessThanOrEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(strings = {"OS과제 제출", "os과제 제출"})
  void detectsAsciiAliasAtAKoreanBoundaryAndRecordsTheMatchedAlias(String content) {
    var result = analyze(content);
    JsonNode tag = operatingSystemsTag(result);

    assertThat(tag.path("matchedAlias").asText()).isEqualTo("OS");
  }

  @Test
  void detectsCanonicalNameWithoutClaimingAnAliasMatch() {
    var result = analyze("운영체제 과제 제출");
    JsonNode tag = operatingSystemsTag(result);

    assertThat(tag.path("matchedAlias").isNull()).isTrue();
  }

  @Test
  void detectsAliasWhenCanonicalNameAndAliasAreBothPresent() {
    var result = analyze("운영체제 OS과제 제출");
    JsonNode tag = operatingSystemsTag(result);

    assertThat(tag.path("matchedAlias").asText()).isEqualTo("OS");
  }

  @Test
  void doesNotResolveOsInsideAnAsciiAlphanumericToken() {
    var result = analyze("postmortem 정리하기");

    assertThat(tagNames(result)).doesNotContain("운영체제");
  }

  @Test
  void boundsTitlesAndObjectsByUnicodeCodePointWithoutSplittingAnEmoji() {
    String expectedBoundary = "가".repeat(199) + "😀";
    String content = expectedBoundary + "나".repeat(5) + " 제출";

    var result = analyze(content);
    String suggestedTitle = result.at("/suggestedTitle/value").asText();
    String itemTitle = result.at("/itemCandidates/0/title").asText();
    String itemObject = result.at("/itemCandidates/0/object").asText();

    assertThat(suggestedTitle).isEqualTo(expectedBoundary);
    assertThat(itemTitle).isEqualTo(expectedBoundary);
    assertThat(itemObject).isEqualTo(expectedBoundary);
    assertThat(suggestedTitle.codePointCount(0, suggestedTitle.length())).isEqualTo(200);
    assertThat(itemObject.codePointCount(0, itemObject.length())).isEqualTo(200);
  }

  @Test
  void keepsSafetySignalsFromDatesBeyondTheCandidateDisplayLimit() {
    var result =
        analyze(
            "2026.08.06 09:00 2026.08.07 09:00 2026.08.08 09:00 "
                + "2026.08.09 09:00 2026.08.10 09:00 다음 주쯤 과제 제출");

    assertThat(result.path("dateCandidates")).hasSize(5);
    assertThat(result.path("ambiguityReasons").toString())
        .contains("IMPRECISE_DATE", "CANDIDATE_LIMIT_EXCEEDED");
    assertThat(result.at("/providerMetadata/route").asText()).isEqualTo("CLOUD_ENRICH");
    assertThat(result.at("/providerMetadata/detectedDateCandidateCount").asInt()).isEqualTo(6);
    assertThat(result.at("/providerMetadata/emittedDateCandidateCount").asInt()).isEqualTo(5);
  }

  @Test
  void fiveExactDatesFitWithoutAnOverflowSignal() {
    var result =
        analyze(
            "2026.08.06 09:00 2026.08.07 09:00 2026.08.08 09:00 "
                + "2026.08.09 09:00 2026.08.10 09:00 과제 제출");

    assertThat(result.path("dateCandidates")).hasSize(5);
    assertThat(result.path("ambiguityReasons").toString())
        .doesNotContain("CANDIDATE_LIMIT_EXCEEDED");
  }

  @Test
  void escalatesEvenWhenEveryDateBeyondTheDisplayLimitIsExact() {
    var result =
        analyze(
            "2026.08.06 09:00 2026.08.07 09:00 2026.08.08 09:00 "
                + "2026.08.09 09:00 2026.08.10 09:00 2026.08.11 09:00 과제 제출");

    assertThat(result.path("dateCandidates")).hasSize(5);
    assertThat(result.path("ambiguityReasons").toString()).contains("CANDIDATE_LIMIT_EXCEEDED");
    assertThat(result.at("/providerMetadata/route").asText()).isEqualTo("CLOUD_ENRICH");
  }

  private ObjectNode analyze(String content) {
    return analyzer.analyze(
        UUID.randomUUID(), 1, content, Instant.parse("2026-08-05T02:00:00Z"), "Asia/Seoul");
  }

  private List<String> tagNames(JsonNode proposal) {
    ArrayList<String> names = new ArrayList<>();
    for (var tag : proposal.path("tagCandidates")) {
      names.add(tag.path("canonicalName").asText());
    }
    return List.copyOf(names);
  }

  private JsonNode operatingSystemsTag(JsonNode proposal) {
    for (var tag : proposal.path("tagCandidates")) {
      if ("운영체제".equals(tag.path("canonicalName").asText())) {
        return tag;
      }
    }
    throw new AssertionError("Operating systems tag candidate was not found");
  }
}
