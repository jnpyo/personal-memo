package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KoreanDateParserTest {
  private static final Instant BASE = Instant.parse("2026-08-05T02:00:00Z");
  private static final String SEOUL = "Asia/Seoul";

  private final KoreanDateParser parser = new KoreanDateParser();

  @Test
  void parsesExplicitDateTimeWithTheSuppliedIanaZone() {
    var result = parser.parse("2026.11.25 18:00 OS 과제 제출", BASE, SEOUL);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().surfaceText()).isEqualTo("2026.11.25 18:00");
    assertThat(result.getFirst().value()).isEqualTo("2026-11-25T18:00:00+09:00");
    assertThat(result.getFirst().precision()).isEqualTo(DatePrecision.EXACT_TIME);
    assertThat(result.getFirst().timeSpecified()).isTrue();
    assertThat(result.getFirst().ambiguityReasons()).isEmpty();
  }

  @Test
  void parsesAnExplicitYearDateAsDateOnlyWithoutAddingAMissingYearSignal() {
    var result = parser.parse("2026.11.25 제출", BASE, SEOUL);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().surfaceText()).isEqualTo("2026.11.25");
    assertThat(result.getFirst().value()).isEqualTo("2026-11-25");
    assertThat(result.getFirst().precision()).isEqualTo(DatePrecision.DATE_ONLY);
    assertThat(result.getFirst().timeSpecified()).isFalse();
    assertThat(result.getFirst().ambiguityReasons())
        .containsExactly(AmbiguityReason.MISSING_TIME);
  }

  @Test
  void longestExplicitRuleWinsWhenDateTimeAndDateOnlyMatchesOverlap() {
    var result = parser.parse("2026.11.25 18:00 제출", BASE, SEOUL);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().surfaceText()).isEqualTo("2026.11.25 18:00");
    assertThat(result.getFirst().value()).isEqualTo("2026-11-25T18:00:00+09:00");
    assertThat(result.getFirst().precision()).isEqualTo(DatePrecision.EXACT_TIME);
  }

  @Test
  void infersNearestFutureYearButPreservesMissingYearAndTimeSignals() {
    var currentYear = parser.parse("11.25 과제", BASE, SEOUL).getFirst();
    var nextYear =
        parser
            .parse("1.2 과제", Instant.parse("2026-12-31T15:30:00Z"), SEOUL)
            .getFirst();

    assertThat(currentYear.value()).isEqualTo("2026-11-25");
    assertThat(currentYear.precision()).isEqualTo(DatePrecision.DATE_ONLY);
    assertThat(currentYear.ambiguityReasons())
        .containsExactlyInAnyOrder(AmbiguityReason.MISSING_YEAR, AmbiguityReason.MISSING_TIME);
    assertThat(nextYear.value()).isEqualTo("2027-01-02");
  }

  @Test
  void resolvesNextWeekTuesdayFromTheSuppliedBaseInsteadOfTheSystemClock() {
    var result = parser.parse("다음 주 화요일까지 제출", BASE, SEOUL).getFirst();

    assertThat(result.surfaceText()).isEqualTo("다음 주 화요일까지");
    assertThat(result.value()).isEqualTo("2026-08-11");
    assertThat(result.precision()).isEqualTo(DatePrecision.DATE_ONLY);
    assertThat(result.ambiguityReasons()).isEmpty();
  }

  @Test
  void neverInventsAnExactValueForApproximateExpressions() {
    var result = parser.parse("다음 주쯤 하고 다음 달에 확인", BASE, SEOUL);

    assertThat(result).hasSize(2);
    assertThat(result)
        .allSatisfy(
            candidate -> {
              assertThat(candidate.value()).isNull();
              assertThat(candidate.precision()).isEqualTo(DatePrecision.APPROXIMATE);
              assertThat(candidate.ambiguityReasons())
                  .containsExactly(AmbiguityReason.IMPRECISE_DATE);
            });
  }

  @Test
  void resolvesYesterdayAsAPastCalendarDateWithoutCreatingATaskSignal() {
    var result = parser.parse("어제 시험 봤음", BASE, SEOUL).getFirst();

    assertThat(result.value()).isEqualTo("2026-08-04");
    assertThat(result.precision()).isEqualTo(DatePrecision.DATE_ONLY);
    assertThat(result.ambiguityReasons()).isEmpty();
  }

  @Test
  void invalidCalendarInputBecomesUnknownWithoutBreakingTheRawFlow() {
    assertThatCode(() -> parser.parse("2026.2.30 18:00 제출", BASE, SEOUL))
        .doesNotThrowAnyException();

    var result = parser.parse("2026.2.30 18:00 제출", BASE, SEOUL).getFirst();
    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
    assertThat(result.ambiguityReasons()).containsExactly(AmbiguityReason.IMPRECISE_DATE);
  }

  @Test
  void fullLookingInvalidClockNeverFallsBackToADateOnlyCandidate() {
    for (String content :
        List.of(
            "2026.11.25 25:00 제출",
            "2026.11.25 18:60 제출",
            "2026.11.25 18:060 제출")) {
      var result = parser.parse(content, BASE, SEOUL);

      assertThat(result).hasSize(1);
      assertThat(result.getFirst().surfaceText())
          .isEqualTo(content.substring(0, content.indexOf(" 제출")));
      assertThat(result.getFirst().value()).isNull();
      assertThat(result.getFirst().precision()).isEqualTo(DatePrecision.UNKNOWN);
      assertThat(result.getFirst().timeSpecified()).isFalse();
      assertThat(result.getFirst().ambiguityReasons())
          .containsExactly(AmbiguityReason.IMPRECISE_DATE);
    }
  }

  @Test
  void rejectsAnExplicitTimeInsideAnIanaDstGap() {
    var result =
        parser.parse(
            "2026.3.8 02:30 예약", BASE, "America/New_York").getFirst();

    assertThat(result.surfaceText()).isEqualTo("2026.3.8 02:30");
    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
    assertThat(result.timeSpecified()).isFalse();
    assertThat(result.ambiguityReasons()).containsExactly(AmbiguityReason.IMPRECISE_DATE);
  }

  @Test
  void rejectsAnExplicitTimeInsideAnIanaDstOverlap() {
    var result =
        parser.parse(
            "2026.11.1 01:30 예약", BASE, "America/New_York").getFirst();

    assertThat(result.surfaceText()).isEqualTo("2026.11.1 01:30");
    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
    assertThat(result.timeSpecified()).isFalse();
    assertThat(result.ambiguityReasons()).containsExactly(AmbiguityReason.IMPRECISE_DATE);
  }

  @Test
  void acceptsAnUnambiguousExplicitTimeAfterTheDstGap() {
    var result =
        parser.parse(
            "2026.3.8 03:30 예약", BASE, "America/New_York").getFirst();

    assertThat(result.value()).isEqualTo("2026-03-08T03:30:00-04:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.EXACT_TIME);
    assertThat(result.timeSpecified()).isTrue();
  }

  @Test
  void yearlessLeapDayChoosesTheFirstRealOccurrenceOnOrAfterTheBaseDate() {
    var beforeLeapDay =
        parser
            .parse("2.29 확인", Instant.parse("2024-02-28T00:00:00Z"), SEOUL)
            .getFirst();
    var afterLeapDay =
        parser
            .parse("2.29 확인", Instant.parse("2024-03-01T00:00:00Z"), SEOUL)
            .getFirst();
    var nonLeapYear =
        parser
            .parse("2.29 확인", Instant.parse("2026-01-01T00:00:00Z"), SEOUL)
            .getFirst();

    assertThat(beforeLeapDay.value()).isEqualTo("2024-02-29");
    assertThat(afterLeapDay.value()).isEqualTo("2028-02-29");
    assertThat(nonLeapYear.value()).isEqualTo("2028-02-29");
    assertThat(afterLeapDay.value()).doesNotEndWith("-02-28");
  }

  @Test
  void dayOnlyDeadlineSkipsMonthsThatDoNotContainTheRequestedDay() {
    var fromFebruary =
        parser
            .parse("31일까지 제출", Instant.parse("2026-02-10T00:00:00Z"), SEOUL)
            .getFirst();
    var fromThirtyDayMonth =
        parser
            .parse("31일까지 제출", Instant.parse("2026-04-10T00:00:00Z"), SEOUL)
            .getFirst();

    assertThat(fromFebruary.value()).isEqualTo("2026-03-31");
    assertThat(fromThirtyDayMonth.value()).isEqualTo("2026-05-31");
    assertThat(fromFebruary.precision()).isEqualTo(DatePrecision.DATE_ONLY);
    assertThat(fromFebruary.ambiguityReasons())
        .containsExactly(AmbiguityReason.MISSING_YEAR);
  }

  @Test
  void invalidZoneAlsoDegradesRecognizedDateTextToUnknown() {
    var result = parser.parse("11.25 제출", BASE, "Not/AZone").getFirst();

    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
  }

  @Test
  void recordsOffsetsAgainstTheOriginalMemoText() {
    String content = "메모: 다음 주 화요일까지 제출";
    var result = parser.parse(content, BASE, SEOUL).getFirst();

    assertThat(content.substring(result.startOffset(), result.endOffset()))
        .isEqualTo(result.surfaceText());
  }
}
