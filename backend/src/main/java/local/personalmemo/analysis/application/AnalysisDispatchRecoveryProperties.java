package local.personalmemo.analysis.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.analysis.dispatch-recovery")
public class AnalysisDispatchRecoveryProperties {
  static final int MAX_BATCH_SIZE = 100;
  static final Duration MIN_FIXED_DELAY = Duration.ofSeconds(1);
  static final Duration MAX_FIXED_DELAY = Duration.ofHours(1);

  private boolean enabled;

  @Min(1) @Max(MAX_BATCH_SIZE) private int batchSize = 25;

  @NotNull private Duration fixedDelay = Duration.ofSeconds(30);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public Duration getFixedDelay() {
    return fixedDelay;
  }

  public void setFixedDelay(Duration fixedDelay) {
    this.fixedDelay = fixedDelay;
  }

  @AssertTrue(message = "fixedDelay must be at least one second and no more than one hour") public boolean isFixedDelayWithinBounds() {
    return fixedDelay != null
        && fixedDelay.compareTo(MIN_FIXED_DELAY) >= 0
        && fixedDelay.compareTo(MAX_FIXED_DELAY) <= 0;
  }
}
