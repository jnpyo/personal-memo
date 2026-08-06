package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import local.personalmemo.analysis.api.AnalysisDtos.Apply;
import local.personalmemo.analysis.api.AnalysisDtos.Due;
import local.personalmemo.analysis.api.AnalysisDtos.Item;
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
  void rejectsImpossibleDatesUnknownZonesAndInventedDateOnlyTimes() {
    assertInvalidDate(
        new Due("2월 30일", "2026-02-30", "DATE_ONLY", "Asia/Seoul", false), "INVALID_DATE_VALUE");
    assertInvalidDate(
        new Due("11.25", "2026-11-25", "DATE_ONLY", "Mars/Olympus", false), "INVALID_TIME_ZONE");
    assertInvalidDate(
        new Due("11.25", "2026-11-25", "DATE_ONLY", "Asia/Seoul", true), "INVALID_DATE_VALUE");
  }

  private Apply apply(Due due) {
    return new Apply(1, "TASK", "과제 제출", List.of(), List.of(new Item("TASK", "과제 제출", due)));
  }

  private void assertInvalidDate(Due due, String expectedCode) {
    assertThatThrownBy(() -> validator.validate(apply(due)))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo(expectedCode));
  }
}
