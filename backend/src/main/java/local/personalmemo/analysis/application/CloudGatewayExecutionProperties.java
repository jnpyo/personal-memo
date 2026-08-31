package local.personalmemo.analysis.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.analysis.cloud-execution")
public class CloudGatewayExecutionProperties {
  static final Duration MAX_TIMEOUT = Duration.ofMinutes(1);

  @NotNull private Duration timeout = Duration.ofSeconds(10);

  @Min(1) @Max(8) private int workers = 2;

  @Min(1) @Max(100) private int queueCapacity = 8;

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  public int getWorkers() {
    return workers;
  }

  public void setWorkers(int workers) {
    this.workers = workers;
  }

  public int getQueueCapacity() {
    return queueCapacity;
  }

  public void setQueueCapacity(int queueCapacity) {
    this.queueCapacity = queueCapacity;
  }

  @AssertTrue(message = "timeout must be greater than zero and no more than one minute") public boolean isTimeoutWithinBounds() {
    return timeout != null
        && !timeout.isZero()
        && !timeout.isNegative()
        && timeout.compareTo(MAX_TIMEOUT) <= 0;
  }
}
