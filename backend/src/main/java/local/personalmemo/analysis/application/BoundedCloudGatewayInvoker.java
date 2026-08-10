package local.personalmemo.analysis.application;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs exactly one cloud gateway attempt in a bounded worker pool outside database transactions.
 */
@Component
public final class BoundedCloudGatewayInvoker implements AutoCloseable {
  private final ThreadPoolExecutor executor;
  private final Duration configuredTimeout;

  public BoundedCloudGatewayInvoker(CloudGatewayExecutionProperties properties) {
    Objects.requireNonNull(properties, "properties");
    Duration timeout = requireTimeout(properties.getTimeout());
    int workers = requireRange(properties.getWorkers(), 1, 8, "workers");
    int queueCapacity = requireRange(properties.getQueueCapacity(), 1, 100, "queueCapacity");
    this.configuredTimeout = timeout;
    this.executor =
        new ThreadPoolExecutor(
            workers,
            workers,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new CustomizableThreadFactory("personal-memo-cloud-"),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public CloudAnalysisResult invoke(CloudGatewayBinding binding, CloudAnalysisRequest request) {
    return invoke(binding, request, configuredTimeout);
  }

  public CloudAnalysisResult invoke(
      CloudGatewayBinding binding, CloudAnalysisRequest request, Duration attemptTimeout) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(request, "request");
    long attemptTimeoutNanos = requireAttemptTimeout(attemptTimeout).toNanos();
    requireNoTransaction();

    Future<CloudAnalysisResult> future;
    try {
      future = executor.submit(() -> binding.execute(request));
    } catch (RejectedExecutionException exception) {
      return CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE);
    }

    try {
      return future.get(attemptTimeoutNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      return CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT);
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw CloudGatewayInvocationException.callerInterrupted();
    } catch (CancellationException exception) {
      throw CloudGatewayInvocationException.unexpectedFailure();
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Error error) {
        throw error;
      }
      throw CloudGatewayInvocationException.unexpectedFailure();
    }
  }

  private void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Cloud gateway invocation must run outside a database transaction.");
    }
  }

  private Duration requireTimeout(Duration timeout) {
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(CloudGatewayExecutionProperties.MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "timeout must be greater than zero and no more than one minute.");
    }
    return timeout;
  }

  private Duration requireAttemptTimeout(Duration attemptTimeout) {
    Duration timeout = requireTimeout(attemptTimeout);
    if (timeout.compareTo(configuredTimeout) > 0) {
      throw new IllegalArgumentException(
          "attemptTimeout must not exceed the configured cloud gateway timeout.");
    }
    return timeout;
  }

  private int requireRange(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(
          field + " must be between " + minimum + " and " + maximum + ".");
    }
    return value;
  }

  int pendingTaskCount() {
    return executor.getQueue().size();
  }

  @Override
  @PreDestroy
  public void close() {
    executor
        .shutdownNow()
        .forEach(
            pending -> {
              if (pending instanceof Future<?> future) {
                future.cancel(true);
              }
            });
  }
}
