package local.personalmemo.analysis.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, side-effect-free extraction for the Korean date expressions in the P0 corpus. */
public final class KoreanDateParser {
  private static final Pattern FULL_DATE_TIME =
      Pattern.compile("(?<!\\d)(\\d{4})\\.(\\d{1,2})\\.(\\d{1,2})\\s+(\\d{1,9}):(\\d{1,9})(?!\\d)");
  private static final Pattern FULL_DATE =
      Pattern.compile("(?<!\\d)(\\d{4})\\.(\\d{1,2})\\.(\\d{1,2})(?!\\d)");
  private static final Pattern MONTH_AND_DAY =
      Pattern.compile("(?<![\\d.])(\\d{1,2})\\.(\\d{1,2})(?!\\d)");
  private static final Pattern KOREAN_MONTH_AND_DAY =
      Pattern.compile("(?<!\\d)(\\d{1,2})월\\s*(\\d{1,2})일");
  private static final Pattern DAY_ONLY_DEADLINE =
      Pattern.compile("(?<![\\d월])([012]?\\d|3[01])일까지");
  private static final Pattern NEXT_WEEK_DAY_TIME =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])다음\\s*주\\s*([월화수목금토일])요일\\s*(오전|오후)\\s*(\\d{1,2})시(?:\\s*(\\d+)분)?(?:\\s*(?:에|부터|까지))?(?=$|\\s|[,.;!?])");
  private static final Pattern APPROXIMATE_NEXT_WEEK_DAY_TIME =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])다음\\s*주\\s*([월화수목금토일])요일\\s*(?:오전|오후)\\s*\\d{1,2}시(?:\\s*\\d+분)?\\s*(?:쯤|경|무렵|정도|전후)");
  private static final Pattern UNSUPPORTED_NEXT_WEEK_DAY_TIME =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])다음\\s*주\\s*([월화수목금토일])요일\\s*(?:오전|오후)\\s*\\d{1,2}시\\s*반(?:\\s*(?:에|부터|까지))?");
  private static final Pattern NEXT_WEEK_DAY =
      Pattern.compile("(?<![\\p{L}\\p{N}])다음\\s*주\\s*([월화수목금토일])요일(?!\\s*(?:오전|오후))(?:까지)?");
  private static final Pattern APPROXIMATE_NEXT_WEEK =
      Pattern.compile("(?<![\\p{L}\\p{N}])다음\\s*주쯤");
  private static final Pattern APPROXIMATE_WEEKEND =
      Pattern.compile("(?<![\\p{L}\\p{N}])(?:(?:이번|다음)\\s*)?주말(?:쯤|\\s*(?:경|무렵)|에)");
  private static final Pattern EVENT_RELATIVE_DEADLINE =
      Pattern.compile("(?<![\\p{L}\\p{N}])다음\\s*(?:회의|수업|약속|행사|미팅|발표|시험|면접|진료|면담|상담)\\s*전(?:까지)?");
  private static final Pattern APPROXIMATE_NEXT_MONTH = Pattern.compile("다음\\s*달(?:에)?");
  private static final Pattern YESTERDAY = Pattern.compile("어제");

  public List<ParsedDate> parse(String content, Instant baseInstant, String timeZoneIdentifier) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(baseInstant, "baseInstant");
    if (timeZoneIdentifier == null || timeZoneIdentifier.isBlank()) {
      throw new IllegalArgumentException("A non-blank IANA time zone is required.");
    }

    ZoneId timeZone;
    try {
      timeZone = ZoneId.of(timeZoneIdentifier);
    } catch (DateTimeException exception) {
      return unknownMatchesWithoutTimeZone(content);
    }
    LocalDate baseDate = baseInstant.atZone(timeZone).toLocalDate();
    List<PrioritizedDate> candidates = new ArrayList<>();

    collect(
        content,
        FULL_DATE_TIME,
        candidates,
        RulePriority.FULL_DATE_TIME,
        matcher -> parseFullDateTime(matcher, timeZone));
    collect(content, FULL_DATE, candidates, RulePriority.FULL_DATE, this::parseFullDate);
    collect(
        content,
        MONTH_AND_DAY,
        candidates,
        RulePriority.MONTH_AND_DAY,
        matcher -> parseMonthAndDay(matcher, baseDate));
    collect(
        content,
        KOREAN_MONTH_AND_DAY,
        candidates,
        RulePriority.KOREAN_MONTH_AND_DAY,
        matcher -> parseKoreanMonthAndDay(matcher, baseDate));
    collect(
        content,
        DAY_ONLY_DEADLINE,
        candidates,
        RulePriority.DAY_ONLY_DEADLINE,
        matcher -> parseDayOnlyDeadline(matcher, baseDate));
    collect(
        content,
        APPROXIMATE_NEXT_WEEK_DAY_TIME,
        candidates,
        RulePriority.APPROXIMATE_NEXT_WEEK_DAY_TIME,
        matcher -> approximate(matcher));
    collect(
        content,
        UNSUPPORTED_NEXT_WEEK_DAY_TIME,
        candidates,
        RulePriority.UNSUPPORTED_NEXT_WEEK_DAY_TIME,
        this::unknown);
    collect(
        content,
        NEXT_WEEK_DAY_TIME,
        candidates,
        RulePriority.NEXT_WEEK_DAY_TIME,
        matcher -> parseNextWeekDayTime(matcher, baseDate, timeZone));
    collect(
        content,
        NEXT_WEEK_DAY,
        candidates,
        RulePriority.NEXT_WEEK_DAY,
        matcher -> parseNextWeekDay(matcher, baseDate));
    collect(
        content,
        APPROXIMATE_NEXT_WEEK,
        candidates,
        RulePriority.APPROXIMATE_NEXT_WEEK,
        matcher -> approximate(matcher));
    collect(
        content,
        APPROXIMATE_WEEKEND,
        candidates,
        RulePriority.APPROXIMATE_WEEKEND,
        matcher -> approximate(matcher));
    collect(
        content,
        EVENT_RELATIVE_DEADLINE,
        candidates,
        RulePriority.EVENT_RELATIVE_DEADLINE,
        this::unknown);
    collect(
        content,
        APPROXIMATE_NEXT_MONTH,
        candidates,
        RulePriority.APPROXIMATE_NEXT_MONTH,
        matcher -> approximate(matcher));
    collect(
        content,
        YESTERDAY,
        candidates,
        RulePriority.YESTERDAY,
        matcher -> parseYesterday(matcher, baseDate));

    candidates.sort(candidateOrder());
    return List.copyOf(removeOverlaps(candidates));
  }

  private void collect(
      String content,
      Pattern pattern,
      List<PrioritizedDate> target,
      RulePriority priority,
      Function<Matcher, ParsedDate> parser) {
    Matcher matcher = pattern.matcher(content);
    while (matcher.find()) {
      target.add(new PrioritizedDate(parser.apply(matcher), priority));
    }
  }

  private ParsedDate parseFullDateTime(Matcher matcher, ZoneId timeZone) {
    try {
      if (matcher.group(4).length() > 2 || matcher.group(5).length() != 2) {
        return unknown(matcher);
      }
      LocalDate date =
          LocalDate.of(
              Integer.parseInt(matcher.group(1)),
              Integer.parseInt(matcher.group(2)),
              Integer.parseInt(matcher.group(3)));
      LocalTime time =
          LocalTime.of(Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)));
      LocalDateTime localDateTime = LocalDateTime.of(date, time);
      List<ZoneOffset> validOffsets = timeZone.getRules().getValidOffsets(localDateTime);
      if (validOffsets.size() != 1) {
        return unknown(matcher);
      }
      ZonedDateTime interpreted =
          ZonedDateTime.ofStrict(localDateTime, validOffsets.getFirst(), timeZone);
      return candidate(
          matcher,
          interpreted.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
          DatePrecision.EXACT_TIME,
          true,
          0.99,
          EnumSet.noneOf(AmbiguityReason.class));
    } catch (DateTimeException exception) {
      return unknown(matcher);
    }
  }

  private ParsedDate parseFullDate(Matcher matcher) {
    try {
      LocalDate date =
          LocalDate.of(
              Integer.parseInt(matcher.group(1)),
              Integer.parseInt(matcher.group(2)),
              Integer.parseInt(matcher.group(3)));
      return candidate(
          matcher,
          date.toString(),
          DatePrecision.DATE_ONLY,
          false,
          0.97,
          EnumSet.of(AmbiguityReason.MISSING_TIME));
    } catch (DateTimeException exception) {
      return unknown(matcher);
    }
  }

  private ParsedDate parseMonthAndDay(Matcher matcher, LocalDate baseDate) {
    try {
      LocalDate date =
          nearestFutureDate(
              baseDate, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
      return candidate(
          matcher,
          date.toString(),
          DatePrecision.DATE_ONLY,
          false,
          0.9,
          EnumSet.of(AmbiguityReason.MISSING_YEAR, AmbiguityReason.MISSING_TIME));
    } catch (DateTimeException exception) {
      return unknown(matcher);
    }
  }

  private ParsedDate parseKoreanMonthAndDay(Matcher matcher, LocalDate baseDate) {
    try {
      LocalDate date =
          nearestFutureDate(
              baseDate, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
      return candidate(
          matcher,
          date.toString(),
          DatePrecision.DATE_ONLY,
          false,
          0.86,
          EnumSet.of(AmbiguityReason.MISSING_YEAR, AmbiguityReason.MISSING_TIME));
    } catch (DateTimeException exception) {
      return unknown(matcher);
    }
  }

  private ParsedDate parseDayOnlyDeadline(Matcher matcher, LocalDate baseDate) {
    try {
      int day = Integer.parseInt(matcher.group(1));
      LocalDate date = nearestFutureDayOfMonth(baseDate, day);
      return candidate(
          matcher,
          date.toString(),
          DatePrecision.DATE_ONLY,
          false,
          0.68,
          EnumSet.of(AmbiguityReason.MISSING_YEAR));
    } catch (DateTimeException exception) {
      return unknown(matcher);
    }
  }

  private ParsedDate parseNextWeekDayTime(Matcher matcher, LocalDate baseDate, ZoneId timeZone) {
    try {
      int twelveHour = Integer.parseInt(matcher.group(3));
      int minute = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
      if (twelveHour < 1 || twelveHour > 12) {
        return unknown(matcher);
      }
      int hour = twelveHour % 12 + ("오후".equals(matcher.group(2)) ? 12 : 0);
      LocalDateTime localDateTime =
          LocalDateTime.of(
              nextWeekDay(baseDate, dayOfWeek(matcher.group(1))), LocalTime.of(hour, minute));
      List<ZoneOffset> validOffsets = timeZone.getRules().getValidOffsets(localDateTime);
      if (validOffsets.size() != 1) {
        return unknown(matcher);
      }
      ZonedDateTime interpreted =
          ZonedDateTime.ofStrict(localDateTime, validOffsets.getFirst(), timeZone);
      return candidate(
          matcher,
          interpreted.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
          DatePrecision.RELATIVE_EXACT,
          true,
          0.97,
          EnumSet.noneOf(AmbiguityReason.class));
    } catch (DateTimeException | NumberFormatException exception) {
      return unknown(matcher);
    }
  }

  private ParsedDate parseNextWeekDay(Matcher matcher, LocalDate baseDate) {
    LocalDate date = nextWeekDay(baseDate, dayOfWeek(matcher.group(1)));
    return candidate(
        matcher,
        date.toString(),
        DatePrecision.DATE_ONLY,
        false,
        0.96,
        EnumSet.noneOf(AmbiguityReason.class));
  }

  private LocalDate nextWeekDay(LocalDate baseDate, DayOfWeek dayOfWeek) {
    LocalDate monday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return monday.plusWeeks(1).with(TemporalAdjusters.nextOrSame(dayOfWeek));
  }

  private DayOfWeek dayOfWeek(String koreanDay) {
    return switch (koreanDay) {
      case "월" -> DayOfWeek.MONDAY;
      case "화" -> DayOfWeek.TUESDAY;
      case "수" -> DayOfWeek.WEDNESDAY;
      case "목" -> DayOfWeek.THURSDAY;
      case "금" -> DayOfWeek.FRIDAY;
      case "토" -> DayOfWeek.SATURDAY;
      case "일" -> DayOfWeek.SUNDAY;
      default -> throw new DateTimeException("Unsupported Korean weekday");
    };
  }

  private ParsedDate parseYesterday(Matcher matcher, LocalDate baseDate) {
    return candidate(
        matcher,
        baseDate.minusDays(1).toString(),
        DatePrecision.DATE_ONLY,
        false,
        0.98,
        EnumSet.noneOf(AmbiguityReason.class));
  }

  private ParsedDate approximate(Matcher matcher) {
    return candidate(
        matcher,
        null,
        DatePrecision.APPROXIMATE,
        false,
        0.45,
        EnumSet.of(AmbiguityReason.IMPRECISE_DATE));
  }

  private ParsedDate unknown(Matcher matcher) {
    return candidate(
        matcher,
        null,
        DatePrecision.UNKNOWN,
        false,
        0.1,
        EnumSet.of(AmbiguityReason.IMPRECISE_DATE));
  }

  private ParsedDate candidate(
      Matcher matcher,
      String value,
      DatePrecision precision,
      boolean timeSpecified,
      double confidence,
      Set<AmbiguityReason> ambiguityReasons) {
    return new ParsedDate(
        matcher.group(),
        matcher.start(),
        matcher.end(),
        value,
        precision,
        timeSpecified,
        confidence,
        Set.copyOf(ambiguityReasons));
  }

  private LocalDate nearestFutureDate(LocalDate baseDate, int month, int day) {
    if (!YearMonth.of(2000, month).isValidDay(day)) {
      throw new DateTimeException("Invalid month and day");
    }

    int candidateYear = baseDate.getYear();
    while (true) {
      YearMonth candidateMonth = YearMonth.of(candidateYear, month);
      if (candidateMonth.isValidDay(day)) {
        LocalDate candidate = candidateMonth.atDay(day);
        if (!candidate.isBefore(baseDate)) {
          return candidate;
        }
      }
      candidateYear = Math.incrementExact(candidateYear);
    }
  }

  private LocalDate nearestFutureDayOfMonth(LocalDate baseDate, int day) {
    if (day < 1 || day > 31) {
      throw new DateTimeException("Invalid day of month");
    }

    YearMonth candidateMonth = YearMonth.from(baseDate);
    while (true) {
      if (candidateMonth.isValidDay(day)) {
        LocalDate candidate = candidateMonth.atDay(day);
        if (!candidate.isBefore(baseDate)) {
          return candidate;
        }
      }
      candidateMonth = candidateMonth.plusMonths(1);
    }
  }

  private Comparator<PrioritizedDate> candidateOrder() {
    return Comparator.comparingInt((PrioritizedDate candidate) -> candidate.date().startOffset())
        .thenComparing(
            Comparator.comparingInt(
                    (PrioritizedDate candidate) ->
                        candidate.date().endOffset() - candidate.date().startOffset())
                .reversed())
        .thenComparingInt(candidate -> candidate.priority().order());
  }

  private List<ParsedDate> removeOverlaps(List<PrioritizedDate> sortedCandidates) {
    List<ParsedDate> accepted = new ArrayList<>();
    for (PrioritizedDate prioritized : sortedCandidates) {
      ParsedDate candidate = prioritized.date();
      boolean overlaps =
          accepted.stream()
              .anyMatch(
                  existing ->
                      candidate.startOffset() < existing.endOffset()
                          && existing.startOffset() < candidate.endOffset());
      if (!overlaps) {
        accepted.add(candidate);
      }
    }
    return accepted;
  }

  private List<ParsedDate> unknownMatchesWithoutTimeZone(String content) {
    List<PrioritizedDate> candidates = new ArrayList<>();
    for (RulePattern rule :
        List.of(
            new RulePattern(FULL_DATE_TIME, RulePriority.FULL_DATE_TIME),
            new RulePattern(FULL_DATE, RulePriority.FULL_DATE),
            new RulePattern(MONTH_AND_DAY, RulePriority.MONTH_AND_DAY),
            new RulePattern(KOREAN_MONTH_AND_DAY, RulePriority.KOREAN_MONTH_AND_DAY),
            new RulePattern(DAY_ONLY_DEADLINE, RulePriority.DAY_ONLY_DEADLINE),
            new RulePattern(
                APPROXIMATE_NEXT_WEEK_DAY_TIME, RulePriority.APPROXIMATE_NEXT_WEEK_DAY_TIME),
            new RulePattern(
                UNSUPPORTED_NEXT_WEEK_DAY_TIME, RulePriority.UNSUPPORTED_NEXT_WEEK_DAY_TIME),
            new RulePattern(NEXT_WEEK_DAY_TIME, RulePriority.NEXT_WEEK_DAY_TIME),
            new RulePattern(NEXT_WEEK_DAY, RulePriority.NEXT_WEEK_DAY),
            new RulePattern(APPROXIMATE_NEXT_WEEK, RulePriority.APPROXIMATE_NEXT_WEEK),
            new RulePattern(APPROXIMATE_WEEKEND, RulePriority.APPROXIMATE_WEEKEND),
            new RulePattern(EVENT_RELATIVE_DEADLINE, RulePriority.EVENT_RELATIVE_DEADLINE),
            new RulePattern(APPROXIMATE_NEXT_MONTH, RulePriority.APPROXIMATE_NEXT_MONTH),
            new RulePattern(YESTERDAY, RulePriority.YESTERDAY))) {
      Matcher matcher = rule.pattern().matcher(content);
      while (matcher.find()) {
        candidates.add(new PrioritizedDate(unknown(matcher), rule.priority()));
      }
    }
    candidates.sort(candidateOrder());
    return List.copyOf(removeOverlaps(candidates));
  }

  private enum RulePriority {
    FULL_DATE_TIME(10),
    FULL_DATE(20),
    MONTH_AND_DAY(30),
    KOREAN_MONTH_AND_DAY(40),
    DAY_ONLY_DEADLINE(50),
    APPROXIMATE_NEXT_WEEK_DAY_TIME(52),
    UNSUPPORTED_NEXT_WEEK_DAY_TIME(53),
    NEXT_WEEK_DAY_TIME(55),
    NEXT_WEEK_DAY(60),
    APPROXIMATE_NEXT_WEEK(70),
    APPROXIMATE_WEEKEND(75),
    EVENT_RELATIVE_DEADLINE(78),
    APPROXIMATE_NEXT_MONTH(80),
    YESTERDAY(90);

    private final int order;

    RulePriority(int order) {
      this.order = order;
    }

    int order() {
      return order;
    }
  }

  private record PrioritizedDate(ParsedDate date, RulePriority priority) {}

  private record RulePattern(Pattern pattern, RulePriority priority) {}

  public record ParsedDate(
      String surfaceText,
      int startOffset,
      int endOffset,
      String value,
      DatePrecision precision,
      boolean timeSpecified,
      double confidence,
      Set<AmbiguityReason> ambiguityReasons) {
    public ParsedDate {
      ambiguityReasons = Set.copyOf(ambiguityReasons);
    }
  }
}
