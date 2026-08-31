package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayAttemptTermination;
import org.junit.jupiter.api.Test;

class CloudGatewayAttemptObservationTest {

  @Test
  void createsASanitizedUnexpectedNotStartedObservation() {
    CloudGatewayAttemptObservation observation =
        CloudGatewayAttemptObservation.unexpectedNotStarted();

    assertThat(observation.termination())
        .isEqualTo(CloudGatewayAttemptTermination.UNEXPECTED_EXCEPTION);
    assertThat(observation.executionState()).isEqualTo(CloudGatewayExecutionState.NOT_STARTED);
    assertThat(observation.elapsedMillis()).isZero();
    assertThat(observation.gatewayResultObserved()).isFalse();
    assertThat(observation.effectiveResult())
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE));
  }

  @Test
  void rejectsIncoherentLocalObservations() {
    assertThatThrownBy(
            () ->
                new CloudGatewayAttemptObservation(
                    CloudGatewayAttemptTermination.GATEWAY_RESULT,
                    CloudGatewayExecutionState.UNKNOWN,
                    0,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A gateway result requires a started execution.");
    assertThatThrownBy(
            () ->
                new CloudGatewayAttemptObservation(
                    CloudGatewayAttemptTermination.TIMEOUT,
                    CloudGatewayExecutionState.STARTED,
                    0,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The effective result does not match the local termination.");
    assertThatThrownBy(
            () ->
                new CloudGatewayAttemptObservation(
                    CloudGatewayAttemptTermination.EXECUTOR_REJECTED,
                    CloudGatewayExecutionState.STARTED,
                    0,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A rejected execution cannot have started.");
    assertThatThrownBy(
            () ->
                new CloudGatewayAttemptObservation(
                    CloudGatewayAttemptTermination.UNEXPECTED_EXCEPTION,
                    CloudGatewayExecutionState.UNKNOWN,
                    -1,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("elapsedMillis must not be negative.");
  }

  @Test
  void preservesUnknownForSubmittedAttemptsWithoutAConfirmedStart() {
    CloudGatewayAttemptObservation timeout =
        new CloudGatewayAttemptObservation(
            CloudGatewayAttemptTermination.TIMEOUT,
            CloudGatewayExecutionState.UNKNOWN,
            5,
            CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT));
    CloudGatewayAttemptObservation interrupted =
        new CloudGatewayAttemptObservation(
            CloudGatewayAttemptTermination.CALLER_INTERRUPTED,
            CloudGatewayExecutionState.UNKNOWN,
            6,
            CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE));
    CloudGatewayAttemptObservation unexpected =
        new CloudGatewayAttemptObservation(
            CloudGatewayAttemptTermination.UNEXPECTED_EXCEPTION,
            CloudGatewayExecutionState.UNKNOWN,
            7,
            CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE));

    assertThat(timeout.executionState()).isEqualTo(CloudGatewayExecutionState.UNKNOWN);
    assertThat(interrupted.executionState()).isEqualTo(CloudGatewayExecutionState.UNKNOWN);
    assertThat(unexpected.executionState()).isEqualTo(CloudGatewayExecutionState.UNKNOWN);
  }

  @Test
  void rejectsDefiniteNotStartedForSubmittedTimeoutsAndInterruptions() {
    assertThatThrownBy(
            () ->
                new CloudGatewayAttemptObservation(
                    CloudGatewayAttemptTermination.TIMEOUT,
                    CloudGatewayExecutionState.NOT_STARTED,
                    0,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A submitted execution without a confirmed start must remain unknown.");
    assertThatThrownBy(
            () ->
                new CloudGatewayAttemptObservation(
                    CloudGatewayAttemptTermination.CALLER_INTERRUPTED,
                    CloudGatewayExecutionState.NOT_STARTED,
                    0,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A submitted execution without a confirmed start must remain unknown.");
  }
}
