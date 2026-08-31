package local.personalmemo.analysis.application;

import java.util.Objects;
import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayAttemptTermination;

/** Sanitized local observation of one bounded cloud gateway attempt. */
public record CloudGatewayAttemptObservation(
    CloudGatewayAttemptTermination termination,
    CloudGatewayExecutionState executionState,
    long elapsedMillis,
    CloudAnalysisResult effectiveResult) {

  public static CloudGatewayAttemptObservation unexpectedNotStarted() {
    return new CloudGatewayAttemptObservation(
        CloudGatewayAttemptTermination.UNEXPECTED_EXCEPTION,
        CloudGatewayExecutionState.NOT_STARTED,
        0,
        CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE));
  }

  public CloudGatewayAttemptObservation {
    termination = Objects.requireNonNull(termination, "termination");
    executionState = Objects.requireNonNull(executionState, "executionState");
    effectiveResult = Objects.requireNonNull(effectiveResult, "effectiveResult");
    if (elapsedMillis < 0) {
      throw new IllegalArgumentException("elapsedMillis must not be negative.");
    }
    requireCoherentExecutionState(termination, executionState);
    requireCoherentEffectiveResult(termination, effectiveResult);
  }

  public boolean gatewayResultObserved() {
    return termination == CloudGatewayAttemptTermination.GATEWAY_RESULT;
  }

  private static void requireCoherentExecutionState(
      CloudGatewayAttemptTermination termination, CloudGatewayExecutionState executionState) {
    if (termination == CloudGatewayAttemptTermination.GATEWAY_RESULT
        && executionState != CloudGatewayExecutionState.STARTED) {
      throw new IllegalArgumentException("A gateway result requires a started execution.");
    }
    if (termination == CloudGatewayAttemptTermination.EXECUTOR_REJECTED
        && executionState != CloudGatewayExecutionState.NOT_STARTED) {
      throw new IllegalArgumentException("A rejected execution cannot have started.");
    }
    if ((termination == CloudGatewayAttemptTermination.TIMEOUT
            || termination == CloudGatewayAttemptTermination.CALLER_INTERRUPTED)
        && executionState == CloudGatewayExecutionState.NOT_STARTED) {
      throw new IllegalArgumentException(
          "A submitted execution without a confirmed start must remain unknown.");
    }
  }

  private static void requireCoherentEffectiveResult(
      CloudGatewayAttemptTermination termination, CloudAnalysisResult effectiveResult) {
    CloudAnalysisFailureReason expectedReason =
        switch (termination) {
          case GATEWAY_RESULT -> null;
          case EXECUTOR_REJECTED -> CloudAnalysisFailureReason.UNAVAILABLE;
          case TIMEOUT -> CloudAnalysisFailureReason.TIMEOUT;
          case CALLER_INTERRUPTED, UNEXPECTED_EXCEPTION ->
              CloudAnalysisFailureReason.UNEXPECTED_FAILURE;
        };
    if (expectedReason != null
        && (!(effectiveResult instanceof CloudAnalysisResult.Failure failure)
            || failure.reason() != expectedReason)) {
      throw new IllegalArgumentException(
          "The effective result does not match the local termination.");
    }
  }

  @Override
  public String toString() {
    return "CloudGatewayAttemptObservation[termination="
        + termination
        + ", executionState="
        + executionState
        + ", elapsedMillis="
        + elapsedMillis
        + ", gatewayResultObserved="
        + gatewayResultObserved()
        + ", effectiveResult=redacted]";
  }
}
