package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AnalysisInvocationConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(AnalysisInvocationConfiguration.class);

  @Test
  void defaultsToUncertaintyOnlyWithApprovedCorrectionContextDisabled() {
    runner.run(
        context -> {
          assertThat(context.getStartupFailure()).isNull();
          AnalysisInvocationProperties properties =
              context.getBean(AnalysisInvocationProperties.class);
          assertThat(properties.getMode()).isEqualTo(AnalysisInvocationMode.UNCERTAINTY_ONLY);
          assertThat(properties.isApprovedCorrectionsEnabled()).isFalse();
          assertThat(properties.getApprovedCorrectionContextK()).isEqualTo(3);
          assertThat(context.getBean(AnalysisInvocationPolicy.class).mode())
              .isEqualTo(AnalysisInvocationMode.UNCERTAINTY_ONLY);
        });
  }

  @Test
  void bindsTheExplicitAiPreferredApprovedCorrectionPolicy() {
    runner
        .withPropertyValues(
            "app.analysis.invocation.mode=AI_PREFERRED",
            "app.analysis.invocation.approved-corrections-enabled=true",
            "app.analysis.invocation.approved-correction-context-k=3")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              AnalysisInvocationProperties properties =
                  context.getBean(AnalysisInvocationProperties.class);
              assertThat(properties.getMode()).isEqualTo(AnalysisInvocationMode.AI_PREFERRED);
              assertThat(properties.isApprovedCorrectionsEnabled()).isTrue();
              assertThat(properties.getApprovedCorrectionContextK()).isEqualTo(3);
              assertThat(context.getBean(AnalysisInvocationPolicy.class).mode())
                  .isEqualTo(AnalysisInvocationMode.AI_PREFERRED);
            });
  }

  @Test
  void rejectsApprovedCorrectionsOutsideAiPreferredMode() {
    runner
        .withPropertyValues("app.analysis.invocation.approved-corrections-enabled=true")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .hasMessageContaining("approved corrections require AI_PREFERRED"));
  }

  @Test
  void rejectsAnyApprovedCorrectionContextBoundOtherThanThree() {
    runner
        .withPropertyValues("app.analysis.invocation.approved-correction-context-k=2")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    runner
        .withPropertyValues(
            "app.analysis.invocation.mode=AI_PREFERRED",
            "app.analysis.invocation.approved-correction-context-k=4")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
  }
}
