package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayAttemptTermination;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudProviderRequestToken;
import local.personalmemo.analysis.domain.CloudTransferMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class BoundedCloudGatewayInvokerTest {
  private static final CloudGatewayDescriptor DESCRIPTOR =
      new CloudGatewayDescriptor(
          "gateway-v1", "provider-v1", "none", "no-network-v1", CloudTransferMode.NO_NETWORK);
  private final ObjectMapper json = new ObjectMapper();
  private final java.util.ArrayList<BoundedCloudGatewayInvoker> invokers =
      new java.util.ArrayList<>();

  @AfterEach
  void closeInvokers() {
    invokers.forEach(BoundedCloudGatewayInvoker::close);
  }

  @Test
  void returnsTheBoundExecutorResult() {
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(1), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR, request -> CloudAnalysisResult.success(request.validatedLocalProposal()));

    CloudAnalysisResult result = invoker.invoke(binding, request());

    assertThat(result).isInstanceOf(CloudAnalysisResult.Success.class);
    assertThat(((CloudAnalysisResult.Success) result).proposal().path("value").asText())
        .isEqualTo("safe");
  }

  @Test
  void observesAGatewayResultWithoutExposingItsProposal() {
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(1), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request ->
                CloudAnalysisResult.success(
                    request.validatedLocalProposal().put("providerDetail", "proposal-secret")));

    CloudGatewayAttemptObservation observation = invoker.observe(binding, request());

    assertThat(observation.termination()).isEqualTo(CloudGatewayAttemptTermination.GATEWAY_RESULT);
    assertThat(observation.executionState()).isEqualTo(CloudGatewayExecutionState.STARTED);
    assertThat(observation.gatewayResultObserved()).isTrue();
    assertThat(observation.elapsedMillis()).isNotNegative();
    assertThat(observation.effectiveResult()).isInstanceOf(CloudAnalysisResult.Success.class);
    assertThat(observation.toString())
        .contains("GATEWAY_RESULT", "effectiveResult=redacted")
        .doesNotContain("proposal-secret", "providerDetail");
  }

  @Test
  void distinguishesAProviderUnavailableResultFromLocalExecutorRejection() {
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(1), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE));

    CloudGatewayAttemptObservation observation = invoker.observe(binding, request());

    assertThat(observation.termination()).isEqualTo(CloudGatewayAttemptTermination.GATEWAY_RESULT);
    assertThat(observation.executionState()).isEqualTo(CloudGatewayExecutionState.STARTED);
    assertThat(observation.gatewayResultObserved()).isTrue();
    assertThat(observation.effectiveResult())
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE));
  }

  @Test
  void cancelsAndReturnsATypedTimeout() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofMillis(500), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              started.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return CloudAnalysisResult.success(request.validatedLocalProposal());
            });

    CloudAnalysisResult result = invoker.invoke(binding, request());

    assertThat(started.getCount()).isZero();
    assertThat(result).isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT));
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void enforcesTheShorterPersistedAttemptTimeout() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(1), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              started.countDown();
              try {
                TimeUnit.MILLISECONDS.sleep(750);
              } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return CloudAnalysisResult.success(request.validatedLocalProposal());
            });

    CloudAnalysisResult result = invoker.invoke(binding, request(), Duration.ofMillis(500));

    assertThat(started.getCount()).isZero();
    assertThat(result).isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT));
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void reportsATimeoutBeforeAQueuedExecutionStarts() throws Exception {
    CountDownLatch workerStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(5), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              workerStarted.countDown();
              try {
                release.await();
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return CloudAnalysisResult.success(request.validatedLocalProposal());
            });
    AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    Thread first = caller(invoker, binding, backgroundFailure);

    try {
      first.start();
      assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();

      CloudGatewayAttemptObservation observation =
          invoker.observe(binding, request(), Duration.ofMillis(50));

      assertThat(observation.termination()).isEqualTo(CloudGatewayAttemptTermination.TIMEOUT);
      assertThat(observation.executionState()).isEqualTo(CloudGatewayExecutionState.UNKNOWN);
      assertThat(observation.gatewayResultObserved()).isFalse();
      assertThat(observation.effectiveResult())
          .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT));
    } finally {
      release.countDown();
      first.join(2_000);
    }
    assertThat(first.isAlive()).isFalse();
    assertThat(backgroundFailure.get()).isNull();
  }

  @Test
  void rejectsAttemptTimeoutsThatExceedEitherBudgetCeiling() {
    BoundedCloudGatewayInvoker oneSecondInvoker = invoker(Duration.ofSeconds(1), 1, 1);
    BoundedCloudGatewayInvoker oneMinuteInvoker = invoker(Duration.ofMinutes(1), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR, request -> CloudAnalysisResult.success(request.validatedLocalProposal()));

    assertThatThrownBy(
            () -> oneSecondInvoker.invoke(binding, request(), Duration.ofSeconds(1).plusNanos(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("attemptTimeout must not exceed the configured cloud gateway timeout.");
    assertThatThrownBy(
            () -> oneMinuteInvoker.invoke(binding, request(), Duration.ofMinutes(1).plusNanos(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("timeout must be greater than zero and no more than one minute.");
  }

  @Test
  void rejectsWorkWhenBothTheWorkerAndQueueAreFull() throws Exception {
    CountDownLatch workerStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(5), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              workerStarted.countDown();
              try {
                release.await();
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return CloudAnalysisResult.success(request.validatedLocalProposal());
            });
    AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    Thread first = caller(invoker, binding, backgroundFailure);
    Thread second = caller(invoker, binding, backgroundFailure);

    try {
      first.start();
      assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
      second.start();
      await(() -> invoker.pendingTaskCount() == 1);

      CloudGatewayAttemptObservation observation = invoker.observe(binding, request());

      assertThat(observation.termination())
          .isEqualTo(CloudGatewayAttemptTermination.EXECUTOR_REJECTED);
      assertThat(observation.executionState()).isEqualTo(CloudGatewayExecutionState.NOT_STARTED);
      assertThat(observation.gatewayResultObserved()).isFalse();
      assertThat(observation.effectiveResult())
          .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE));
      assertThat(invoker.invoke(binding, request()))
          .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE));
    } finally {
      release.countDown();
      first.join(2_000);
      second.join(2_000);
    }
    assertThat(first.isAlive()).isFalse();
    assertThat(second.isAlive()).isFalse();
    assertThat(backgroundFailure.get()).isNull();
  }

  @Test
  void sanitizesExecutorExceptionsWithoutRetainingTheirCause() {
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(1), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              throw new IllegalStateException("provider-api-key-secret");
            });

    assertThatThrownBy(() -> invoker.invoke(binding, request()))
        .isInstanceOfSatisfying(
            CloudGatewayInvocationException.class,
            exception -> {
              assertThat(exception.reason())
                  .isEqualTo(CloudGatewayInvocationException.Reason.UNEXPECTED_FAILURE);
              assertThat(exception.toString()).doesNotContain("provider-api-key-secret");
            })
        .hasMessage("Cloud gateway invocation failed unexpectedly.")
        .hasNoCause();
  }

  @Test
  void observesExecutorExceptionsAsSanitizedUnexpectedFailures() {
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(1), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              throw new IllegalStateException("provider-api-key-secret");
            });

    CloudGatewayAttemptObservation observation = invoker.observe(binding, request());

    assertThat(observation.termination())
        .isEqualTo(CloudGatewayAttemptTermination.UNEXPECTED_EXCEPTION);
    assertThat(observation.executionState()).isEqualTo(CloudGatewayExecutionState.STARTED);
    assertThat(observation.gatewayResultObserved()).isFalse();
    assertThat(observation.effectiveResult())
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE));
    assertThat(observation.toString())
        .contains("UNEXPECTED_EXCEPTION", "effectiveResult=redacted")
        .doesNotContain("provider-api-key-secret", "IllegalStateException");
  }

  @Test
  void sanitizesExecutorErrorsWithoutRetainingTheirTypeTextOrCause() {
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(1), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              throw new AssertionError("provider-error-secret");
            });

    CloudGatewayAttemptObservation observation = invoker.observe(binding, request());

    assertThat(observation.termination())
        .isEqualTo(CloudGatewayAttemptTermination.UNEXPECTED_EXCEPTION);
    assertThat(observation.effectiveResult())
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE));
    assertThat(observation.toString()).doesNotContain("provider-error-secret", "AssertionError");
    assertThatThrownBy(() -> invoker.invoke(binding, request()))
        .isInstanceOfSatisfying(
            CloudGatewayInvocationException.class,
            exception -> {
              assertThat(exception.reason())
                  .isEqualTo(CloudGatewayInvocationException.Reason.UNEXPECTED_FAILURE);
              assertThat(exception.toString())
                  .contains("detail=redacted")
                  .doesNotContain("provider-error-secret", "AssertionError");
            })
        .hasNoCause();
  }

  @Test
  void derivesElapsedMillisFromMonotonicNanoseconds() {
    assertThat(BoundedCloudGatewayInvoker.elapsedMillis(5, 9_999_999)).isEqualTo(9);
    assertThat(BoundedCloudGatewayInvoker.elapsedMillis(100, 99)).isZero();
  }

  @Test
  void restoresTheCallerInterruptAndExposesARecoveryReason() throws Exception {
    CountDownLatch workerStarted = new CountDownLatch(1);
    CountDownLatch workerInterrupted = new CountDownLatch(1);
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(5), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              workerStarted.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException exception) {
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return CloudAnalysisResult.success(request.validatedLocalProposal());
            });
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interruptRestored = new AtomicBoolean();
    Thread caller =
        new Thread(
            () -> {
              try {
                invoker.invoke(binding, request());
              } catch (Throwable throwable) {
                failure.set(throwable);
                interruptRestored.set(Thread.currentThread().isInterrupted());
              }
            });

    caller.start();
    assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
    caller.interrupt();
    caller.join(2_000);

    assertThat(caller.isAlive()).isFalse();
    assertThat(failure.get()).isInstanceOf(CloudGatewayInvocationException.class);
    assertThat(((CloudGatewayInvocationException) failure.get()).reason())
        .isEqualTo(CloudGatewayInvocationException.Reason.CALLER_INTERRUPTED);
    assertThat(failure.get()).hasMessage("Cloud gateway invocation was interrupted.");
    assertThat(failure.get()).hasNoCause();
    assertThat(interruptRestored).isTrue();
    assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void observesCallerInterruptionWithoutClaimingARemoteResult() throws Exception {
    CountDownLatch workerStarted = new CountDownLatch(1);
    CountDownLatch workerInterrupted = new CountDownLatch(1);
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(5), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              workerStarted.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException exception) {
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return CloudAnalysisResult.success(request.validatedLocalProposal());
            });
    AtomicReference<CloudGatewayAttemptObservation> observed = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interruptRestored = new AtomicBoolean();
    Thread caller =
        new Thread(
            () -> {
              try {
                observed.set(invoker.observe(binding, request()));
                interruptRestored.set(Thread.currentThread().isInterrupted());
              } catch (Throwable throwable) {
                failure.set(throwable);
              }
            });

    caller.start();
    assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
    caller.interrupt();
    caller.join(2_000);

    assertThat(caller.isAlive()).isFalse();
    assertThat(failure.get()).isNull();
    assertThat(interruptRestored).isTrue();
    assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(observed.get().termination())
        .isEqualTo(CloudGatewayAttemptTermination.CALLER_INTERRUPTED);
    assertThat(observed.get().executionState()).isEqualTo(CloudGatewayExecutionState.STARTED);
    assertThat(observed.get().gatewayResultObserved()).isFalse();
    assertThat(observed.get().effectiveResult())
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE));
  }

  @Test
  void rejectsInvocationWhileTheCallerOwnsATransaction() {
    AtomicBoolean executed = new AtomicBoolean();
    BoundedCloudGatewayInvoker invoker = invoker(Duration.ofSeconds(1), 1, 1);
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              executed.set(true);
              return CloudAnalysisResult.success(request.validatedLocalProposal());
            });

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> invoker.invoke(binding, request()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Cloud gateway invocation must run outside a database transaction.");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    assertThat(executed).isFalse();
  }

  private BoundedCloudGatewayInvoker invoker(Duration timeout, int workers, int queueCapacity) {
    CloudGatewayExecutionProperties properties = new CloudGatewayExecutionProperties();
    properties.setTimeout(timeout);
    properties.setWorkers(workers);
    properties.setQueueCapacity(queueCapacity);
    BoundedCloudGatewayInvoker invoker = new BoundedCloudGatewayInvoker(properties);
    invokers.add(invoker);
    return invoker;
  }

  private Thread caller(
      BoundedCloudGatewayInvoker invoker,
      CloudGatewayBinding binding,
      AtomicReference<Throwable> failure) {
    return new Thread(
        () -> {
          try {
            invoker.invoke(binding, request());
          } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
          }
        });
  }

  private void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(1);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private CloudAnalysisRequest request() {
    return new CloudAnalysisRequest(
        json.createObjectNode().put("value", "safe"),
        List.of(),
        "field-policy-v1",
        DESCRIPTOR,
        Optional.empty(),
        Optional.empty(),
        CloudProviderRequestToken.issue(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "ANALYSIS_START",
            "bounded-invoker-test",
            "b".repeat(64)));
  }
}
