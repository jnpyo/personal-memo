package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import local.personalmemo.analysis.api.AnalysisDtos.Apply;
import local.personalmemo.analysis.api.AnalysisDtos.Due;
import local.personalmemo.analysis.api.AnalysisDtos.EventSchedule;
import local.personalmemo.analysis.api.AnalysisDtos.Item;
import local.personalmemo.analysis.api.AnalysisDtos.SelectedRelation;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.taxonomy.domain.TagNormalizer;
import org.junit.jupiter.api.Test;

class AnalysisApplicationValidatorTest {

  private final AnalysisApplicationValidator validator =
      new AnalysisApplicationValidator(new TagNormalizer());

  @Test
  void dateOnlyPreservesCalendarDateWithoutInventingAnInstant() {
    Due due = new Due("11.25", "2026-11-25", "DATE_ONLY", "Asia/Seoul", false);

    var validated = validator.validate(apply(due)).items().getFirst().due();

    assertThat(validated.dueLocalDate()).isEqualTo(LocalDate.of(2026, 11, 25));
    assertThat(validated.dueInstant()).isNull();
    assertThat(validated.surfaceText()).isEqualTo("11.25");
    assertThat(validated.timeZone()).isEqualTo("Asia/Seoul");
  }

  @Test
  void exactTimeRequiresOffsetAndConvertsToUtcInstant() {
    Due due = new Due("11월 25일 18시", "2026-11-25T18:00:00+09:00", "EXACT_TIME", "Asia/Seoul", true);

    var validated = validator.validate(apply(due)).items().getFirst().due();

    assertThat(validated.dueInstant()).isEqualTo(Instant.parse("2026-11-25T09:00:00Z"));
    assertThat(validated.dueLocalDate()).isNull();
  }

  @Test
  void canonicalizesDueTimeZoneFromTheImmutableMemoRevision() {
    Due due = new Due("11.25", "2026-11-25", "DATE_ONLY", "Asia/Seoul", false);
    var original = validator.validate(apply(due));

    var canonical = validator.canonicalizeDueTimeZone(original, "America/New_York");

    assertThat(original.items().getFirst().due().timeZone()).isEqualTo("Asia/Seoul");
    assertThat(canonical.items().getFirst().due().timeZone()).isEqualTo("America/New_York");
    assertThat(canonical.items().getFirst().due().dueLocalDate())
        .isEqualTo(LocalDate.of(2026, 11, 25));
  }

  @Test
  void timedEventPreservesAnExplicitMissingEndWithoutInventingDuration() {
    EventSchedule schedule =
        new EventSchedule("TIMED", "2026-08-24T18:00:00+09:00", null, "Asia/Seoul");

    var validated = validator.validate(eventApply(schedule)).items().getFirst().eventSchedule();

    assertThat(validated.startInstant()).isEqualTo(Instant.parse("2026-08-24T09:00:00Z"));
    assertThat(validated.endInstant()).isNull();
    assertThat(validated.startLocalDate()).isNull();
  }

  @Test
  void timedEventRejectsFractionalSecondsThatCannotBeRepresentedInRfc5545() {
    EventSchedule fractionalStart =
        new EventSchedule("TIMED", "2026-08-24T18:00:00.100+09:00", null, "Asia/Seoul");
    EventSchedule fractionalEnd =
        new EventSchedule(
            "TIMED", "2026-08-24T18:00:00+09:00", "2026-08-24T19:00:00.100+09:00", "Asia/Seoul");

    assertInvalidEvent(eventApply(fractionalStart), "INVALID_EVENT_SCHEDULE_PRECISION");
    assertInvalidEvent(eventApply(fractionalEnd), "INVALID_EVENT_SCHEDULE_PRECISION");
  }

  @Test
  void timedEventOffsetMustMatchTheImmutableRevisionZoneAndRejectDstGaps() {
    EventSchedule mismatched =
        new EventSchedule("TIMED", "2026-08-24T18:00:00+09:00", null, "Asia/Seoul");
    var mismatchedSelection = validator.validate(eventApply(mismatched));

    assertThatThrownBy(
            () -> validator.canonicalizeDueTimeZone(mismatchedSelection, "America/New_York"))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).code())
        .isEqualTo("EVENT_SCHEDULE_ZONE_OFFSET_MISMATCH");

    EventSchedule gap =
        new EventSchedule("TIMED", "2026-03-08T02:30:00-05:00", null, "America/New_York");
    var gapSelection = validator.validate(eventApply(gap));

    assertThatThrownBy(() -> validator.canonicalizeDueTimeZone(gapSelection, "America/New_York"))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).code())
        .isEqualTo("EVENT_SCHEDULE_ZONE_OFFSET_MISMATCH");
  }

  @Test
  void timedEventPreservesEitherExplicitOffsetDuringADstOverlap() {
    for (String start : List.of("2026-11-01T01:30:00-04:00", "2026-11-01T01:30:00-05:00")) {
      EventSchedule overlap = new EventSchedule("TIMED", start, null, "America/New_York");

      var canonical =
          validator.canonicalizeDueTimeZone(
              validator.validate(eventApply(overlap)), "America/New_York");

      assertThat(canonical.items().getFirst().eventSchedule().originalStart()).isEqualTo(start);
    }
  }

  @Test
  void allDayEventPreservesExclusiveEndAndCanonicalRevisionTimeZone() {
    EventSchedule schedule = new EventSchedule("ALL_DAY", "2026-08-24", "2026-08-26", "Asia/Seoul");
    var original = validator.validate(eventApply(schedule));

    var canonical = validator.canonicalizeDueTimeZone(original, "America/New_York");
    var validated = canonical.items().getFirst().eventSchedule();

    assertThat(validated.startLocalDate()).isEqualTo(LocalDate.of(2026, 8, 24));
    assertThat(validated.endLocalDateExclusive()).isEqualTo(LocalDate.of(2026, 8, 26));
    assertThat(validated.timeZone()).isEqualTo("America/New_York");
  }

  @Test
  void rejectsEventScheduleWithoutVersionWrongKindOrInvalidRange() {
    EventSchedule timed =
        new EventSchedule(
            "TIMED", "2026-08-24T18:00:00+09:00", "2026-08-24T17:00:00+09:00", "Asia/Seoul");
    assertInvalidEvent(eventApply(timed), "INVALID_EVENT_SCHEDULE_RANGE");

    EventSchedule allDay = new EventSchedule("ALL_DAY", "2026-08-24", "2026-08-24", "Asia/Seoul");
    assertInvalidEvent(eventApply(allDay), "INVALID_EVENT_SCHEDULE_RANGE");
    EventSchedule validAllDay =
        new EventSchedule("ALL_DAY", "2026-08-24", "2026-08-25", "Asia/Seoul");

    Apply taskWithSchedule =
        new Apply(
            1,
            "TASK",
            "과제 제출",
            List.of(),
            List.of(new Item(null, "TASK", "과제 제출", null, validAllDay)),
            List.of(),
            "2");
    assertInvalidEvent(taskWithSchedule, "EVENT_SCHEDULE_REQUIRES_EVENT");

    Apply missingVersion =
        new Apply(
            1,
            "EVENT",
            "회의",
            List.of(),
            List.of(new Item(null, "EVENT", "회의", null, validAllDay)),
            List.of(),
            null);
    assertInvalidEvent(missingVersion, "EVENT_SCHEDULE_VERSION_REQUIRED");
  }

  @Test
  void rejectsImpossibleDatesUnknownZonesAndInventedDateOnlyTimes() {
    assertInvalidDate(
        new Due("2월 30일", "2026-02-30", "DATE_ONLY", "Asia/Seoul", false), "INVALID_DATE_VALUE");
    assertInvalidDate(
        new Due("11.25", "2026-11-25", "DATE_ONLY", "Mars/Olympus", false), "INVALID_TIME_ZONE");
    assertInvalidDate(
        new Due("11.25", "2026-11-25", "DATE_ONLY", "Asia/Seoul", true), "INVALID_DATE_VALUE");
  }

  @Test
  void preservesOpaqueProposalCandidateIdentityExactly() {
    String candidateId = " item-1 ";

    var validated =
        validator.validate(
            new Apply(
                1,
                "TASK",
                "과제 제출",
                List.of(),
                List.of(new Item(candidateId, "TASK", "과제 제출", null)),
                List.of()));

    assertThat(validated.items().getFirst().proposalCandidateId()).isEqualTo(candidateId);
  }

  @Test
  void validatesProposalCandidateIdentityByUnicodeCodePointAndScalarSafety() {
    String supplementary = "😀";
    assertThatCode(() -> validator.validate(applyWithCandidateId(supplementary.repeat(100))))
        .doesNotThrowAnyException();

    assertInvalidCandidateId(supplementary.repeat(101));
    assertInvalidCandidateId("\uD83D");
    assertInvalidCandidateId("item\0one");
  }

  @Test
  void preservesMissingVersusExplicitEmptyRelationSelection() {
    assertThat(validator.validate(apply(null)).selectedRelations()).isNull();

    Apply explicitEmpty =
        new Apply(
            1, "TASK", "과제 제출", List.of(), List.of(new Item("TASK", "과제 제출", null)), List.of());

    assertThat(validator.validate(explicitEmpty).selectedRelations()).isEmpty();
  }

  @Test
  void rejectsDuplicateProposalRelationIndexes() {
    Apply duplicate =
        new Apply(
            1,
            "TASK",
            "과제 제출",
            List.of(),
            List.of(new Item("item-1", "TASK", "과제 제출", null)),
            List.of(new SelectedRelation(0), new SelectedRelation(0)));

    assertThatThrownBy(() -> validator.validate(duplicate))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_RELATION_SELECTION"));
  }

  private Apply apply(Due due) {
    return new Apply(1, "TASK", "과제 제출", List.of(), List.of(new Item("TASK", "과제 제출", due)));
  }

  private Apply applyWithCandidateId(String candidateId) {
    return new Apply(
        1,
        "TASK",
        "과제 제출",
        List.of(),
        List.of(new Item(candidateId, "TASK", "과제 제출", null)),
        List.of());
  }

  private Apply eventApply(EventSchedule schedule) {
    return new Apply(
        1,
        "EVENT",
        "회의",
        List.of(),
        List.of(new Item(null, "EVENT", "회의", null, schedule)),
        List.of(),
        "2");
  }

  private void assertInvalidCandidateId(String candidateId) {
    assertThatThrownBy(() -> validator.validate(applyWithCandidateId(candidateId)))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_RELATION_SELECTION"));
  }

  private void assertInvalidDate(Due due, String expectedCode) {
    assertThatThrownBy(() -> validator.validate(apply(due)))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo(expectedCode));
  }

  private void assertInvalidEvent(Apply apply, String expectedCode) {
    assertThatThrownBy(() -> validator.validate(apply))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo(expectedCode));
  }
}
