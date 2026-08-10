package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AnalysisDispatchRecoveryConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(AnalysisDispatchRecoveryConfiguration.class)
          .withBean(AnalysisService.class, () -> mock(AnalysisService.class));

  @Test
  void remainsDisabledOutsideProduction() {
    runner
        .withPropertyValues(
            "spring.profiles.active=test",
            "app.analysis.dispatch-recovery.enabled=true",
            "app.analysis.dispatch-recovery.fixed-delay=1h")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBeansOfType(AnalysisDispatchRecoveryWorker.class)).isEmpty();
            });
  }

  @Test
  void remainsDisabledWithoutTheExplicitProductionFlag() {
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBeansOfType(AnalysisDispatchRecoveryWorker.class)).isEmpty();
            });
  }

  @Test
  void startsOnlyWhenProductionExplicitlyEnablesRecovery() {
    runner
        .withPropertyValues(
            "spring.profiles.active=prod",
            "app.analysis.dispatch-recovery.enabled=true",
            "app.analysis.dispatch-recovery.batch-size=7",
            "app.analysis.dispatch-recovery.fixed-delay=1h")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBeansOfType(AnalysisDispatchRecoveryWorker.class)).hasSize(1);
              assertThat(context.getBean(AnalysisDispatchRecoveryProperties.class).getBatchSize())
                  .isEqualTo(7);
            });
  }
}
