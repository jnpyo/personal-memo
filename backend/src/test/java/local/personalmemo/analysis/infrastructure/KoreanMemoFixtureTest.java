package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class KoreanMemoFixtureTest {

  private static final Set<String> FAKE_ANALYZER_CASES =
      Set.of("clear-explicit-task", "clear-date-only-task");
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void fixtureIdsAreUniqueAndEveryCaseDeclaresItsExpectedRoute() throws Exception {
    JsonNode fixtures = fixtures();
    Set<String> ids = new HashSet<>();

    for (JsonNode fixture : fixtures) {
      assertThat(fixture.path("id").asText()).isNotBlank();
      assertThat(ids.add(fixture.path("id").asText())).isTrue();
      assertThat(fixture.path("content").asText()).isNotBlank();
      assertThat(fixture.path("expectedRoute").asText())
          .isIn("LOCAL_REVIEW", "CLOUD_ENRICH", "USER_INPUT_NEEDED", "PENDING_OFFLINE");
      assertThat(fixture.path("expectedTypes").isArray()).isTrue();
      assertThat(fixture.path("expectedSignals").isArray()).isTrue();
    }
  }

  @TestFactory
  Stream<DynamicTest> clearCasesStayCompatibleWithFakeAnalyzer() throws Exception {
    FakeAnalyzer analyzer = new FakeAnalyzer(json);
    return StreamSupport.stream(fixtures().spliterator(), false)
        .filter(fixture -> FAKE_ANALYZER_CASES.contains(fixture.path("id").asText()))
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture.path("id").asText(),
                    () -> {
                      var proposal =
                          analyzer.analyze(
                              UUID.randomUUID(),
                              1,
                              fixture.path("content").asText(),
                              Instant.parse("2026-08-05T02:00:00Z"),
                              "Asia/Seoul");
                      assertThat(proposal.path("schemaVersion").asText()).isEqualTo("1");
                      assertThat(proposal.at("/typeCandidates/0/value").asText())
                          .isEqualTo(fixture.at("/expectedTypes/0").asText());
                      for (JsonNode signal : fixture.path("expectedSignals")) {
                        assertThat(proposal.path("ambiguityReasons").toString())
                            .contains(signal.asText());
                      }
                    }));
  }

  private JsonNode fixtures() throws Exception {
    Path path = Path.of("..", "fixtures", "korean-memo-cases.json");
    Assumptions.assumeTrue(
        Files.exists(path),
        "Root fixtures are unavailable when only the backend Docker build context is mounted");
    return json.readTree(Files.readString(path));
  }
}
