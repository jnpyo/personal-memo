package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class KoreanMemoFixtureTest {
  private final ObjectMapper json = new ObjectMapper();
  private final FakeAnalyzer analyzer = new FakeAnalyzer(json);
  private final DeterministicAmbiguityGate ambiguityGate = new DeterministicAmbiguityGate();
  private final AnalysisProposalValidator validator = new AnalysisProposalValidator();

  @Test
  void fixtureIdsAreUniqueAndEveryCaseDeclaresItsExpectedRoute() throws Exception {
    JsonNode fixtures = fixtures();
    Set<String> ids = new HashSet<>();

    assertThat(fixtures).hasSize(12);
    for (JsonNode fixture : fixtures) {
      assertThat(fixture.path("id").asText()).isNotBlank();
      assertThat(ids.add(fixture.path("id").asText())).isTrue();
      assertThat(fixture.path("content").asText()).isNotBlank();
      assertThat(fixture.path("datasetVersion").asText()).isEqualTo("2");
      assertThat(fixture.path("split").asText()).isEqualTo("REGRESSION");
      assertThat(fixture.path("baseInstant").asText()).isNotBlank();
      assertThat(fixture.path("timeZone").asText()).isNotBlank();
      assertThat(fixture.path("expectedRoute").asText()).isIn("LOCAL_REVIEW", "CLOUD_ENRICH");
      assertThat(fixture.path("analyzerExpectedRoute").asText())
          .isIn("LOCAL_REVIEW", "CLOUD_ENRICH");
      assertThat(fixture.path("expectedTypes").isArray()).isTrue();
      assertThat(fixture.path("expectedSignals").isArray()).isTrue();
      assertThat(fixture.path("analyzerExpectedSignals").isArray()).isTrue();
      assertThat(fixture.path("expectedDates").isObject()).isTrue();
      assertThat(fixture.path("expectedItems").isObject()).isTrue();
    }
  }

  @TestFactory
  Stream<DynamicTest> everyKoreanMemoFixtureProducesAValidDeterministicProposal() throws Exception {
    return StreamSupport.stream(fixtures().spliterator(), false)
        .map(
            fixture ->
                DynamicTest.dynamicTest(fixture.path("id").asText(), () -> verifyFixture(fixture)));
  }

  private void verifyFixture(JsonNode fixture) {
    UUID memoId = UUID.randomUUID();
    String content = fixture.path("content").asText();
    JsonNode proposal =
        analyzer.analyze(
            memoId,
            3,
            content,
            java.time.Instant.parse(fixture.path("baseInstant").asText()),
            fixture.path("timeZone").asText());

    validator.validate(proposal, memoId, 3, content.length());
    String expectedAnalyzerRoute = fixture.path("analyzerExpectedRoute").asText();
    assertThat(proposal.at("/providerMetadata/route").asText()).isEqualTo(expectedAnalyzerRoute);
    assertThat(ambiguityGate.route(ambiguityGate.routingSignals(proposal)).name())
        .isEqualTo(expectedAnalyzerRoute);
    assertThat(textValues(proposal.path("typeCandidates"), "value"))
        .containsExactlyElementsOf(textValues(fixture.path("expectedTypes"), null));

    assertThat(proposal.path("itemCandidates")).hasSizeLessThanOrEqualTo(3);

    verifyCaseSpecificContract(fixture.path("id").asText(), proposal);
  }

  private void verifyCaseSpecificContract(String id, JsonNode proposal) {
    switch (id) {
      case "clear-explicit-task" -> {
        assertThat(proposal.at("/dateCandidates/0/value").asText())
            .isEqualTo("2026-11-25T18:00:00+09:00");
        assertThat(proposal.at("/dateCandidates/0/precision").asText()).isEqualTo("EXACT_TIME");
        assertOwnerNeutralOsAlias(proposal);
      }
      case "clear-date-only-task" -> {
        assertThat(proposal.at("/dateCandidates/0/value").asText()).isEqualTo("2026-11-25");
        assertThat(proposal.at("/dateCandidates/0/surfaceText").asText()).isEqualTo("11.25");
        assertOwnerNeutralOsAlias(proposal);
      }
      case "relative-exact-task" ->
          assertThat(proposal.at("/dateCandidates/0/value").asText()).isEqualTo("2026-08-11");
      case "imprecise-reference-task" -> {
        assertThat(proposal.at("/dateCandidates/0/value").isNull()).isTrue();
        assertThat(proposal.at("/dateCandidates/0/precision").asText()).isEqualTo("APPROXIMATE");
      }
      case "information-only" -> assertNoTaskItems(proposal);
      case "mixed-information-task" ->
          assertThat(textValues(proposal.path("itemCandidates"), "kind"))
              .containsExactly("INFORMATION", "TASK");
      case "missing-action" -> {
        assertThat(proposal.path("itemCandidates")).hasSize(1);
        assertThat(proposal.at("/itemCandidates/0/kind").asText()).isEqualTo("RECORD");
        assertThat(proposal.at("/typeCandidates/0/value").asText()).isEqualTo("UNKNOWN");
      }
      case "conflicting-dates" -> {
        assertThat(proposal.path("dateCandidates")).hasSize(2);
        assertThat(textValues(proposal.path("itemCandidates"), "kind"))
            .containsExactly("TASK", "TASK");
      }
      case "new-topic" -> {
        JsonNode tag = proposal.path("tagCandidates").get(0);
        assertThat(tag.path("canonicalName").asText()).isEqualTo("유리패드");
        assertThat(tag.path("existingTagId").isNull()).isTrue();
        assertThat(tag.path("isNewProposal").asBoolean()).isTrue();
      }
      case "past-event" -> assertNoTaskItems(proposal);
      case "prompt-injection" -> {
        assertNoTaskItems(proposal);
        assertThat(proposal.at("/providerMetadata/toolCalls").asInt()).isZero();
      }
      case "long-ambiguous-note" -> assertThat(proposal.path("itemCandidates")).hasSize(3);
      default -> throw new AssertionError("Uncovered fixture: " + id);
    }
  }

  private void assertOwnerNeutralOsAlias(JsonNode proposal) {
    JsonNode tag = proposal.path("tagCandidates").get(0);
    assertThat(tag.path("existingTagId").isNull()).isTrue();
    assertThat(tag.path("canonicalName").asText()).isEqualTo("운영체제");
    assertThat(tag.path("matchedAlias").asText()).isEqualTo("OS");
    assertThat(tag.path("isNewProposal").asBoolean()).isTrue();
  }

  private void assertNoTaskItems(JsonNode proposal) {
    assertThat(textValues(proposal.path("itemCandidates"), "kind")).doesNotContain("TASK");
  }

  private List<String> textValues(JsonNode array, String field) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      values.add(field == null ? value.asText() : value.path(field).asText());
    }
    return values;
  }

  private JsonNode fixtures() throws Exception {
    JsonNode bundled;
    try (InputStream stream =
        KoreanMemoFixtureTest.class.getResourceAsStream("/fixtures/korean-memo-cases.json")) {
      if (stream == null) {
        throw new IllegalStateException("Bundled Korean memo fixtures are missing.");
      }
      bundled = json.readTree(stream);
    }

    Path rootFixture = Path.of("..", "fixtures", "korean-memo-cases.json");
    if (Files.exists(rootFixture)) {
      JsonNode root = json.readTree(Files.readString(rootFixture));
      assertThat(bundled)
          .as("backend test fixture copy must stay identical to the root evaluation fixture")
          .isEqualTo(root);
    }
    return bundled;
  }
}
