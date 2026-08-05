package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FakeAnalyzerTest {

  private final FakeAnalyzer analyzer = new FakeAnalyzer(new ObjectMapper());

  @Test
  void preservesDateSurfaceAndResolvesAlias() {
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
    assertThat(result.at("/tagCandidates/0/isNewProposal").asBoolean()).isFalse();
  }

  @Test
  void rollsDateOnlyCandidateIntoNextYearWhenMonthAndDayAlreadyPassed() {
    var result =
        analyzer.analyze(
            UUID.randomUUID(),
            4,
            "1.2 과제 제출",
            Instant.parse("2026-12-31T15:30:00Z"),
            "Asia/Seoul");

    assertThat(result.at("/dateCandidates/0/value").asText()).isEqualTo("2027-01-02");
    assertThat(result.path("memoRevision").asInt()).isEqualTo(4);
  }

  @Test
  void doesNotCreateCanonicalRecordsAndCarriesVersionMetadataOnlyInProposal() {
    UUID memoId = UUID.randomUUID();
    var result =
        analyzer.analyze(
            memoId,
            2,
            "아이디어 기록",
            Instant.parse("2026-08-05T02:00:00Z"),
            "Asia/Seoul");

    assertThat(result.path("schemaVersion").asText()).isEqualTo("1");
    assertThat(result.path("memoId").asText()).isEqualTo(memoId.toString());
    assertThat(result.at("/providerMetadata/analyzerVersion").asText()).isEqualTo("fake-v1");
    assertThat(result.path("relationCandidates").isArray()).isTrue();
    assertThat(result.path("itemCandidates").size()).isLessThanOrEqualTo(3);
  }
}
