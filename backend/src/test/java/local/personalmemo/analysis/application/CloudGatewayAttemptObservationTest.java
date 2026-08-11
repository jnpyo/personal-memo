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
    assertThat(observation.executionStarted()).isFalse();
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
                    false,
                    0,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A gateway result requires a started execution.");
    assertThatThrownBy(
            () ->
                new CloudGatewayAttemptObservation(
                    CloudGatewayAttemptTermination.TIMEOUT,
                    true,
                    0,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The effective result does not match the local termination.");
    assertThatThrownBy(
            () ->
                new CloudGatewayAttemptObservation(
                    CloudGatewayAttemptTermination.EXECUTOR_REJECTED,
                    true,
                    0,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A rejected execution cannot have started.");
    assertThatThrownBy(
            () ->
                new CloudGatewayAttemptObservation(
                    CloudGatewayAttemptTermination.UNEXPECTED_EXCEPTION,
                    false,
                    -1,
                    CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("elapsedMillis must not be negative.");
  }
}
