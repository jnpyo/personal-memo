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
import java.util.concurrent.atomic.AtomicBoolean;
import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayAttemptTermination;
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
    CloudGatewayAttemptObservation observation = observe(binding, request, attemptTimeout);
    return switch (observation.termination()) {
      case CALLER_INTERRUPTED -> throw CloudGatewayInvocationException.callerInterrupted();
      case UNEXPECTED_EXCEPTION -> throw CloudGatewayInvocationException.unexpectedFailure();
      case GATEWAY_RESULT, EXECUTOR_REJECTED, TIMEOUT -> observation.effectiveResult();
    };
  }

  public CloudGatewayAttemptObservation observe(
      CloudGatewayBinding binding, CloudAnalysisRequest request) {
    return observe(binding, request, configuredTimeout);
  }

  public CloudGatewayAttemptObservation observe(
      CloudGatewayBinding binding, CloudAnalysisRequest request, Duration attemptTimeout) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(request, "request");
    long attemptTimeoutNanos = requireAttemptTimeout(attemptTimeout).toNanos();
    requireNoTransaction();
    long startedAtNanos = System.nanoTime();
    AtomicBoolean executionStarted = new AtomicBoolean();

    Future<CloudAnalysisResult> future;
    try {
      future =
          executor.submit(
              () -> {
                executionStarted.set(true);
                return binding.execute(request);
              });
    } catch (RejectedExecutionException exception) {
      return observation(
          CloudGatewayAttemptTermination.EXECUTOR_REJECTED,
          CloudGatewayExecutionState.NOT_STARTED,
          startedAtNanos,
          CloudAnalysisFailureReason.UNAVAILABLE);
    }

    try {
      CloudAnalysisResult result = future.get(attemptTimeoutNanos, TimeUnit.NANOSECONDS);
      return new CloudGatewayAttemptObservation(
          CloudGatewayAttemptTermination.GATEWAY_RESULT,
          CloudGatewayExecutionState.STARTED,
          elapsedMillis(startedAtNanos, System.nanoTime()),
          result);
    } catch (TimeoutException exception) {
      future.cancel(true);
      return observation(
          CloudGatewayAttemptTermination.TIMEOUT,
          observedExecutionState(executionStarted),
          startedAtNanos,
          CloudAnalysisFailureReason.TIMEOUT);
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      return observation(
          CloudGatewayAttemptTermination.CALLER_INTERRUPTED,
          observedExecutionState(executionStarted),
          startedAtNanos,
          CloudAnalysisFailureReason.UNEXPECTED_FAILURE);
    } catch (CancellationException exception) {
      return observation(
          CloudGatewayAttemptTermination.UNEXPECTED_EXCEPTION,
          observedExecutionState(executionStarted),
          startedAtNanos,
          CloudAnalysisFailureReason.UNEXPECTED_FAILURE);
    } catch (ExecutionException exception) {
      return observation(
          CloudGatewayAttemptTermination.UNEXPECTED_EXCEPTION,
          observedExecutionState(executionStarted),
          startedAtNanos,
          CloudAnalysisFailureReason.UNEXPECTED_FAILURE);
    }
  }

  private CloudGatewayAttemptObservation observation(
      CloudGatewayAttemptTermination termination,
      CloudGatewayExecutionState executionState,
      long startedAtNanos,
      CloudAnalysisFailureReason effectiveFailureReason) {
    return new CloudGatewayAttemptObservation(
        termination,
        executionState,
        elapsedMillis(startedAtNanos, System.nanoTime()),
        CloudAnalysisResult.failure(effectiveFailureReason));
  }

  private CloudGatewayExecutionState observedExecutionState(AtomicBoolean executionStarted) {
    // Cancellation can race with callable entry, so false after submission is not proof that the
    // gateway execution never started.
    return executionStarted.get()
        ? CloudGatewayExecutionState.STARTED
        : CloudGatewayExecutionState.UNKNOWN;
  }

  static long elapsedMillis(long startedAtNanos, long finishedAtNanos) {
    long elapsedNanos = finishedAtNanos - startedAtNanos;
    return elapsedNanos <= 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
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
