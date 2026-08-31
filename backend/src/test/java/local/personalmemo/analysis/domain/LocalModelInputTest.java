package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalModelInputTest {
  private static final Instant REFERENCE = Instant.parse("2026-08-21T09:00:00Z");

  @Test
  void acceptsTheBoundedMemoRevisionAndRedactsItFromStringForm() {
    LocalModelInput input = new LocalModelInput("6시 디스코드 접속하기", REFERENCE, "Asia/Seoul");

    assertThat(input.memoContent()).isEqualTo("6시 디스코드 접속하기");
    assertThat(input.referenceInstant()).isEqualTo(REFERENCE);
    assertThat(input.timeZone()).isEqualTo("Asia/Seoul");
    assertThat(input.toString())
        .contains("memoContent=redacted", "Asia/Seoul")
        .doesNotContain("디스코드");
  }

  @Test
  void enforcesMemoUnicodeAndSizeBounds() {
    assertThatCode(
            () ->
                new LocalModelInput(
                    "가".repeat(LocalModelInput.MAX_MEMO_UTF16_UNITS), REFERENCE, "Asia/Seoul"))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                new LocalModelInput(
                    "가".repeat(LocalModelInput.MAX_MEMO_UTF16_UNITS + 1), REFERENCE, "Asia/Seoul"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LocalModelInput("\uD83D", REFERENCE, "Asia/Seoul"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresARecognizedBoundedTimeZone() {
    assertThatThrownBy(() -> new LocalModelInput("memo", REFERENCE, "Not/AZone"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LocalModelInput("memo", REFERENCE, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsOnlyBoundedSafeHintsFromTheSameMemoAndRedactsThem() {
    ApprovedCorrectionContext.Hint hint = new ApprovedCorrectionContext.Hint("접속하기", "TASK");
    LocalModelInput input =
        new LocalModelInput("6시 디스코드 접속하기", REFERENCE, "Asia/Seoul", List.of(hint));

    assertThat(input.approvedCorrectionHints()).containsExactly(hint);
    assertThat(input.toString())
        .contains("approvedCorrectionHintCount=1/redacted")
        .doesNotContain("접속하기");
    assertThatThrownBy(() -> new LocalModelInput("회의 기록", REFERENCE, "Asia/Seoul", List.of(hint)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
