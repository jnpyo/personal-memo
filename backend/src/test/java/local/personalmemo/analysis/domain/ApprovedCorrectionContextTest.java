package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovedCorrectionContextTest {

  @Test
  void defensivelyCopiesBoundedSignalsAndRehydratesOnlyFromTargetRevision() {
    String content = "😀 6시 디스코드 접속하기";
    int start = content.indexOf("접속하기");
    List<ApprovedCorrectionContext.Signal> mutable =
        new ArrayList<>(
            List.of(new ApprovedCorrectionContext.Signal(start, start + "접속하기".length(), "TASK")));

    ApprovedCorrectionContext context =
        new ApprovedCorrectionContext(ApprovedCorrectionContext.CURRENT_VERSION, mutable);
    mutable.clear();

    assertThat(context.signalCount()).isOne();
    assertThat(context.rehydrate(content))
        .containsExactly(new ApprovedCorrectionContext.Hint("접속하기", "TASK"));
    assertThatThrownBy(() -> context.signals().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsUnsupportedOversizedDuplicateOrInvalidSignals() {
    ApprovedCorrectionContext.Signal first = new ApprovedCorrectionContext.Signal(0, 4, "TASK");

    assertThatThrownBy(() -> new ApprovedCorrectionContext("other", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ApprovedCorrectionContext(
                    ApprovedCorrectionContext.CURRENT_VERSION,
                    List.of(
                        first,
                        new ApprovedCorrectionContext.Signal(4, 8, "EVENT"),
                        new ApprovedCorrectionContext.Signal(8, 12, "IDEA"),
                        new ApprovedCorrectionContext.Signal(12, 16, "RECORD"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ApprovedCorrectionContext(
                    ApprovedCorrectionContext.CURRENT_VERSION,
                    List.of(first, new ApprovedCorrectionContext.Signal(0, 4, "EVENT"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovedCorrectionContext.Signal(-1, 4, "TASK"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovedCorrectionContext.Signal(0, 4, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovedCorrectionContext.Signal(0, 4, "UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesStaleOrNonUniqueRehydration() {
    ApprovedCorrectionContext context =
        new ApprovedCorrectionContext(
            ApprovedCorrectionContext.CURRENT_VERSION,
            List.of(new ApprovedCorrectionContext.Signal(0, 4, "TASK")));

    assertThatThrownBy(() -> context.rehydrate("접속하기 접속하기"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    assertThatThrownBy(() -> context.rehydrate("수정된 메모"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void diagnosticsAreRedacted() {
    ApprovedCorrectionContext.Signal signal = new ApprovedCorrectionContext.Signal(0, 4, "TASK");
    ApprovedCorrectionContext context =
        new ApprovedCorrectionContext(ApprovedCorrectionContext.CURRENT_VERSION, List.of(signal));
    ApprovedCorrectionContext.Hint hint = new ApprovedCorrectionContext.Hint("접속하기", "TASK");

    assertThat(signal.toString()).isEqualTo("Signal[redacted]").doesNotContain("TASK");
    assertThat(hint.toString()).isEqualTo("Hint[redacted]").doesNotContain("접속하기");
    assertThat(context.toString())
        .contains("signalCount=1", "signals=redacted")
        .doesNotContain("TASK", "접속하기");
  }
}
