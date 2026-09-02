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
    assertThat(result.getFirst().ambiguityReasons()).containsExactly(AmbiguityReason.MISSING_TIME);
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
    var nextYear = parser.parse("1.2 과제", Instant.parse("2026-12-31T15:30:00Z"), SEOUL).getFirst();

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
  void resolvesAnyNextWeekWeekdayWithAnExplicitKoreanTime() {
    var result = parser.parse("다음주 금요일 오전 9시 20분 상담", BASE, SEOUL).getFirst();

    assertThat(result.surfaceText()).isEqualTo("다음주 금요일 오전 9시 20분");
    assertThat(result.value()).isEqualTo("2026-08-14T09:20:00+09:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
    assertThat(result.timeSpecified()).isTrue();
    assertThat(result.ambiguityReasons()).isEmpty();
  }

  @Test
  void resolvesAnExplicitKoreanTimeBeforeATimeParticle() {
    var result = parser.parse("다음 주 금요일 오후 3시에 방문", BASE, SEOUL).getFirst();

    assertThat(result.value()).isEqualTo("2026-08-14T15:00:00+09:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
    assertThat(result.timeSpecified()).isTrue();
  }

  @Test
  void resolvesTodayAfternoonFromTheSuppliedBaseAndIanaZone() {
    var result = parser.parse("오늘 오후 6시 디스코드 접속하기", BASE, SEOUL).getFirst();

    assertThat(result.surfaceText()).isEqualTo("오늘 오후 6시");
    assertThat(result.value()).isEqualTo("2026-08-05T18:00:00+09:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
    assertThat(result.timeSpecified()).isTrue();
    assertThat(result.ambiguityReasons()).isEmpty();
    assertThat(parser.unparsedTemporalCueCount(List.of(result))).isZero();
  }

  @Test
  void invalidRelativeTwelveHourClockStaysUnknown() {
    var result = parser.parse("오늘 오후 14시 회의", BASE, SEOUL).getFirst();

    assertThat(result.surfaceText()).isEqualTo("오늘 오후 14시");
    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
  }

  @Test
  void resolvesABareTwelveHourClockToTheEarliestStrictlyFutureTimeToday() {
    var dates = parser.parse("6시 디스코드 접속하기", BASE, SEOUL);

    assertThat(dates).hasSize(1);
    assertThat(dates.getFirst().surfaceText()).isEqualTo("6시");
    assertThat(dates.getFirst().value()).isEqualTo("2026-08-05T18:00:00+09:00");
    assertThat(dates.getFirst().precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
    assertThat(dates.getFirst().timeSpecified()).isTrue();
    assertThat(dates.getFirst().ambiguityReasons()).isEmpty();
    assertThat(parser.unparsedTemporalCueCount(dates)).isZero();
  }

  @Test
  void choosesTheMorningCandidateWhenItIsStillInTheFuture() {
    var result = parser.parse("7시 약속", Instant.parse("2026-08-04T21:30:00Z"), SEOUL).getFirst();

    assertThat(result.value()).isEqualTo("2026-08-05T07:00:00+09:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
  }

  @Test
  void anEqualMorningInstantIsNotFutureAndFallsThroughToTheAfternoonCandidate() {
    var result = parser.parse("7시 약속", Instant.parse("2026-08-04T22:00:00Z"), SEOUL).getFirst();

    assertThat(result.value()).isEqualTo("2026-08-05T19:00:00+09:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
  }

  @Test
  void leavesABareClockUnknownWhenBothSameDayCandidatesHavePassed() {
    var result = parser.parse("7시 약속", Instant.parse("2026-08-05T11:00:00Z"), SEOUL).getFirst();

    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
    assertThat(result.timeSpecified()).isFalse();
    assertThat(result.ambiguityReasons()).containsExactly(AmbiguityReason.IMPRECISE_DATE);
  }

  @Test
  void resolvesOptionalValidMinutesAndTheAtParticleWithoutDroppingTheSurface() {
    var result = parser.parse("7시 30분에 접속", BASE, SEOUL).getFirst();

    assertThat(result.surfaceText()).isEqualTo("7시 30분에");
    assertThat(result.value()).isEqualTo("2026-08-05T19:30:00+09:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
  }

  @Test
  void explicitMeridiemUsesOnlyThatSameDayCandidate() {
    var futureMorning =
        parser.parse("오전 7시 약속", Instant.parse("2026-08-04T21:30:00Z"), SEOUL).getFirst();
    var passedMorning = parser.parse("오전 7시 약속", BASE, SEOUL).getFirst();
    var futureAfternoon = parser.parse("오후 7시 약속", BASE, SEOUL).getFirst();

    assertThat(futureMorning.value()).isEqualTo("2026-08-05T07:00:00+09:00");
    assertThat(passedMorning.value()).isNull();
    assertThat(passedMorning.precision()).isEqualTo(DatePrecision.UNKNOWN);
    assertThat(futureAfternoon.value()).isEqualTo("2026-08-05T19:00:00+09:00");
  }

  @Test
  void treatsKoreanThirteenToTwentyThreeAndColonClocksAsTwentyFourHourTime() {
    var koreanWithoutMinutes = parser.parse("19시에 출발", BASE, SEOUL).getFirst();
    var korean = parser.parse("18시 5분에 출발", BASE, SEOUL).getFirst();
    var paddedMorning =
        parser.parse("07시 출발", Instant.parse("2026-08-04T21:30:00Z"), SEOUL).getFirst();
    var colon = parser.parse("18:30에 출발", BASE, SEOUL).getFirst();
    var passedColon = parser.parse("7:00 출발", BASE, SEOUL).getFirst();

    assertThat(koreanWithoutMinutes.value()).isEqualTo("2026-08-05T19:00:00+09:00");
    assertThat(korean.value()).isEqualTo("2026-08-05T18:05:00+09:00");
    assertThat(korean.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
    assertThat(paddedMorning.value()).isEqualTo("2026-08-05T07:00:00+09:00");
    assertThat(colon.value()).isEqualTo("2026-08-05T18:30:00+09:00");
    assertThat(colon.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
    assertThat(passedColon.value()).isNull();
    assertThat(passedColon.precision()).isEqualTo(DatePrecision.UNKNOWN);
  }

  @Test
  void mapsTwelveToMidnightAndNoonBeforeChoosingTheFutureCandidate() {
    var beforeNoon = parser.parse("12시 약속", BASE, SEOUL).getFirst();
    var atNoon = parser.parse("12시 약속", Instant.parse("2026-08-05T03:00:00Z"), SEOUL).getFirst();

    assertThat(beforeNoon.value()).isEqualTo("2026-08-05T12:00:00+09:00");
    assertThat(atNoon.value()).isNull();
    assertThat(atNoon.precision()).isEqualTo(DatePrecision.UNKNOWN);
  }

  @Test
  void usesTheSourceZoneLocalDateInsteadOfTheJvmDate() {
    Instant sameCapture = Instant.parse("2026-08-05T15:30:00Z");
    var seoul = parser.parse("1시 약속", sameCapture, SEOUL).getFirst();
    var newYork = parser.parse("1시 약속", sameCapture, "America/New_York").getFirst();

    assertThat(seoul.value()).isEqualTo("2026-08-06T01:00:00+09:00");
    assertThat(newYork.value()).isEqualTo("2026-08-05T13:00:00-04:00");
  }

  @Test
  void aMissingBlankOrInvalidSourceZoneLeavesADatelessClockUnknown() {
    for (String timeZone : List.of("", "   ", "Not/AZone")) {
      var result = parser.parse("7시 약속", BASE, timeZone).getFirst();

      assertThat(result.value()).isNull();
      assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
    }

    var nullZoneResult = parser.parse("7시 약속", BASE, null).getFirst();
    assertThat(nullZoneResult.value()).isNull();
    assertThat(nullZoneResult.precision()).isEqualTo(DatePrecision.UNKNOWN);
  }

  @Test
  void skipsAGappedMorningCandidateAndCanUseTheValidAfternoonCandidate() {
    var result =
        parser
            .parse("2시 30분 약속", Instant.parse("2026-03-08T05:30:00Z"), "America/New_York")
            .getFirst();

    assertThat(result.value()).isEqualTo("2026-03-08T14:30:00-04:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
  }

  @Test
  void failsClosedWhenAnOverlapCandidateCouldStillBeInTheFuture() {
    var result =
        parser
            .parse("1시 30분 약속", Instant.parse("2026-11-01T04:30:00Z"), "America/New_York")
            .getFirst();

    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
  }

  @Test
  void canUseTheAfternoonCandidateAfterBothOverlapInstantsHavePassed() {
    var result =
        parser
            .parse("1시 30분 약속", Instant.parse("2026-11-01T07:00:00Z"), "America/New_York")
            .getFirst();

    assertThat(result.value()).isEqualTo("2026-11-01T13:30:00-05:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
  }

  @Test
  void doesNotReinterpretAClockQualifiedByADateInTheSameClause() {
    for (String content :
        List.of(
            "내일 7시 약속",
            "내일은 7시 약속",
            "내일은 오후 7시 약속",
            "내일 저녁 7시 약속",
            "금요일 7시 약속",
            "금요일에는 7시 약속",
            "이번 주 금요일 7시 약속",
            "9월 2일에는 7시 약속",
            "11.25 18:00 약속")) {
      assertThat(parser.parse(content, BASE, SEOUL))
          .noneSatisfy(
              candidate ->
                  assertThat(candidate.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT));
    }
  }

  @Test
  void allowsAnIndependentDatelessClockAfterASentenceBoundary() {
    var result = parser.parse("내일 일정. 7시 운동", BASE, SEOUL).getFirst();

    assertThat(result.surfaceText()).isEqualTo("7시");
    assertThat(result.value()).isEqualTo("2026-08-05T19:00:00+09:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
  }

  @Test
  void unsupportedParticlesHedgesHalfHoursAndInvalidMinutesNeverBecomeExact() {
    for (String content :
        List.of(
            "6시부터 접속",
            "6시까지 접속",
            "6시 쯤 접속",
            "6시경 접속",
            "6시 무렵 접속",
            "6시 정도 접속",
            "6시 전후 접속",
            "6시반 접속",
            "6시 60분 접속")) {
      assertThat(parser.parse(content, BASE, SEOUL))
          .noneSatisfy(
              candidate ->
                  assertThat(candidate.precision()).isEqualTo(DatePrecision.RELATIVE_EXACT));
    }
  }

  @Test
  void unsupportedTemporalTailsStayUnknownWithoutTruncatingToABareClock() {
    for (String content :
        List.of(
            "6시 이전 접속",
            "6시 이후 접속",
            "6시 안에 접속",
            "6시 전 접속",
            "6시 후 접속",
            "6시 30초 접속",
            "18:00 이후 접속",
            "6시 쯤에 접속",
            "6시경에 접속",
            "6시 무렵에는 접속",
            "6시 정도에도 접속",
            "6시반에 접속",
            "6시 넘어서 접속",
            "6시 지나서 접속",
            "6시 정각 접속",
            "18:00 정각에 접속")) {
      var result = parser.parse(content, BASE, SEOUL).getFirst();
      var expectedSurface = content.substring(0, content.lastIndexOf(' '));

      assertThat(result.surfaceText()).isEqualTo(expectedSurface);
      assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
      assertThat(result.value()).isNull();
      assertThat(result.surfaceText()).isNotIn("6시", "18:00");
      assertThat(parser.unparsedTemporalCueCount(List.of(result))).isEqualTo(1);
    }
  }

  @Test
  void doesNotCountAClockAlreadyCoveredByASupportedDateRule() {
    var dates = parser.parse("다음 주 금요일 오후 3시에 방문", BASE, SEOUL);

    assertThat(dates).hasSize(1);
    assertThat(dates.getFirst().precision()).isEqualTo(DatePrecision.RELATIVE_EXACT);
    assertThat(parser.unparsedTemporalCueCount(dates)).isZero();
  }

  @Test
  void doesNotTreatADurationAsABareClockCue() {
    var dates = parser.parse("6시간 집중 기록", BASE, SEOUL);

    assertThat(dates).isEmpty();
    assertThat(parser.unparsedTemporalCueCount(dates)).isZero();
  }

  @Test
  void resolvesAnyNextWeekWeekdayAsDateOnlyWhenTimeIsAbsent() {
    var result = parser.parse("다음 주 목요일까지 검수", BASE, SEOUL).getFirst();

    assertThat(result.value()).isEqualTo("2026-08-13");
    assertThat(result.precision()).isEqualTo(DatePrecision.DATE_ONLY);
    assertThat(result.timeSpecified()).isFalse();
  }

  @Test
  void keepsWeekendAndEventRelativeDeadlinesImprecise() {
    var result = parser.parse("이번 주말 무렵 정리하고 다음 면접 전까지 제출", BASE, SEOUL);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).surfaceText()).isEqualTo("이번 주말 무렵");
    assertThat(result.get(0).precision()).isEqualTo(DatePrecision.APPROXIMATE);
    assertThat(result.get(1).surfaceText()).isEqualTo("다음 면접 전까지");
    assertThat(result.get(1).precision()).isEqualTo(DatePrecision.UNKNOWN);
    assertThat(result)
        .allSatisfy(
            candidate -> {
              assertThat(candidate.value()).isNull();
              assertThat(candidate.ambiguityReasons())
                  .containsExactly(AmbiguityReason.IMPRECISE_DATE);
            });
  }

  @Test
  void approximateRelativeTimeNeverBecomesAnExactInstant() {
    var result = parser.parse("다음 주 금요일 오후 3시쯤 방문", BASE, SEOUL).getFirst();

    assertThat(result.surfaceText()).isEqualTo("다음 주 금요일 오후 3시쯤");
    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.APPROXIMATE);
    assertThat(result.ambiguityReasons()).containsExactly(AmbiguityReason.IMPRECISE_DATE);
  }

  @Test
  void invalidRelativeClockDoesNotFallBackToTheShorterDateRule() {
    var result = parser.parse("다음 주 금요일 오후 14시 방문", BASE, SEOUL).getFirst();

    assertThat(result.surfaceText()).isEqualTo("다음 주 금요일 오후 14시");
    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
  }

  @Test
  void neverTruncatesUnsupportedOrMalformedRelativeTimesIntoExactInstants() {
    var result =
        List.of("다음 주 금요일 오후 3시 123분 방문", "다음 주 금요일 오후 3시반 방문", "다음 주 금요일 오후 3시 반 방문").stream()
            .map(content -> parser.parse(content, BASE, SEOUL).getFirst())
            .toList();

    assertThat(result)
        .allSatisfy(
            candidate -> {
              assertThat(candidate.value()).isNull();
              assertThat(candidate.precision()).isEqualTo(DatePrecision.UNKNOWN);
              assertThat(candidate.ambiguityReasons())
                  .containsExactly(AmbiguityReason.IMPRECISE_DATE);
            });
  }

  @Test
  void treatsRelativeTimeHedgesAsApproximateInsteadOfExact() {
    var result =
        List.of("정도", "전후").stream()
            .map(hedge -> parser.parse("다음 주 금요일 오후 3시 " + hedge + " 방문", BASE, SEOUL).getFirst())
            .toList();

    assertThat(result)
        .allSatisfy(
            candidate -> {
              assertThat(candidate.value()).isNull();
              assertThat(candidate.precision()).isEqualTo(DatePrecision.APPROXIMATE);
            });
  }

  @Test
  void avoidsBareWeekendAndEmbeddedNextWeekFalsePositives() {
    assertThat(parser.parse("주말 분위기의 음악 기록", BASE, SEOUL)).isEmpty();
    assertThat(parser.parse("다다음 주 금요일 일정", BASE, SEOUL)).isEmpty();
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
        List.of("2026.11.25 25:00 제출", "2026.11.25 18:60 제출", "2026.11.25 18:060 제출")) {
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
    var result = parser.parse("2026.3.8 02:30 예약", BASE, "America/New_York").getFirst();

    assertThat(result.surfaceText()).isEqualTo("2026.3.8 02:30");
    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
    assertThat(result.timeSpecified()).isFalse();
    assertThat(result.ambiguityReasons()).containsExactly(AmbiguityReason.IMPRECISE_DATE);
  }

  @Test
  void rejectsAnExplicitTimeInsideAnIanaDstOverlap() {
    var result = parser.parse("2026.11.1 01:30 예약", BASE, "America/New_York").getFirst();

    assertThat(result.surfaceText()).isEqualTo("2026.11.1 01:30");
    assertThat(result.value()).isNull();
    assertThat(result.precision()).isEqualTo(DatePrecision.UNKNOWN);
    assertThat(result.timeSpecified()).isFalse();
    assertThat(result.ambiguityReasons()).containsExactly(AmbiguityReason.IMPRECISE_DATE);
  }

  @Test
  void acceptsAnUnambiguousExplicitTimeAfterTheDstGap() {
    var result = parser.parse("2026.3.8 03:30 예약", BASE, "America/New_York").getFirst();

    assertThat(result.value()).isEqualTo("2026-03-08T03:30:00-04:00");
    assertThat(result.precision()).isEqualTo(DatePrecision.EXACT_TIME);
    assertThat(result.timeSpecified()).isTrue();
  }

  @Test
  void yearlessLeapDayChoosesTheFirstRealOccurrenceOnOrAfterTheBaseDate() {
    var beforeLeapDay =
        parser.parse("2.29 확인", Instant.parse("2024-02-28T00:00:00Z"), SEOUL).getFirst();
    var afterLeapDay =
        parser.parse("2.29 확인", Instant.parse("2024-03-01T00:00:00Z"), SEOUL).getFirst();
    var nonLeapYear =
        parser.parse("2.29 확인", Instant.parse("2026-01-01T00:00:00Z"), SEOUL).getFirst();

    assertThat(beforeLeapDay.value()).isEqualTo("2024-02-29");
    assertThat(afterLeapDay.value()).isEqualTo("2028-02-29");
    assertThat(nonLeapYear.value()).isEqualTo("2028-02-29");
    assertThat(afterLeapDay.value()).doesNotEndWith("-02-28");
  }

  @Test
  void dayOnlyDeadlineSkipsMonthsThatDoNotContainTheRequestedDay() {
    var fromFebruary =
        parser.parse("31일까지 제출", Instant.parse("2026-02-10T00:00:00Z"), SEOUL).getFirst();
    var fromThirtyDayMonth =
        parser.parse("31일까지 제출", Instant.parse("2026-04-10T00:00:00Z"), SEOUL).getFirst();

    assertThat(fromFebruary.value()).isEqualTo("2026-03-31");
    assertThat(fromThirtyDayMonth.value()).isEqualTo("2026-05-31");
    assertThat(fromFebruary.precision()).isEqualTo(DatePrecision.DATE_ONLY);
    assertThat(fromFebruary.ambiguityReasons()).containsExactly(AmbiguityReason.MISSING_YEAR);
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
