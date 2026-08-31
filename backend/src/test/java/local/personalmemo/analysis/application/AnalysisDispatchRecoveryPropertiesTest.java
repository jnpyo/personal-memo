package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AnalysisDispatchRecoveryPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsBoundedProductionValues() {
    runner
        .withPropertyValues(
            "app.analysis.dispatch-recovery.enabled=true",
            "app.analysis.dispatch-recovery.batch-size=100",
            "app.analysis.dispatch-recovery.fixed-delay=1h")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              AnalysisDispatchRecoveryProperties properties =
                  context.getBean(AnalysisDispatchRecoveryProperties.class);
              assertThat(properties.isEnabled()).isTrue();
              assertThat(properties.getBatchSize()).isEqualTo(100);
              assertThat(properties.getFixedDelay()).isEqualTo(Duration.ofHours(1));
            });
  }

  @Test
  void rejectsAnUnboundedBatch() {
    runner
        .withPropertyValues("app.analysis.dispatch-recovery.batch-size=101")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .hasMessageContaining(
                        "Binding validation errors on app.analysis.dispatch-recovery")
                    .hasMessageContaining("batchSize"));
  }

  @Test
  void rejectsASubSecondOrExcessiveFixedDelay() {
    runner
        .withPropertyValues("app.analysis.dispatch-recovery.fixed-delay=999ms")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    runner
        .withPropertyValues("app.analysis.dispatch-recovery.fixed-delay=1h1s")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AnalysisDispatchRecoveryProperties.class)
  static class PropertiesConfiguration {}
}
