package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayAttemptTermination;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudTransferMode;
import org.junit.jupiter.api.Test;

class AnalysisServiceAttemptObservationTest {
  private static final CloudGatewayDescriptor FAKE_DESCRIPTOR =
      new CloudGatewayDescriptor(
          "fake-v1", "fake-provider", "none", "consent-v1", CloudTransferMode.NO_NETWORK);
  private static final CloudGatewayDescriptor MODEL_DESCRIPTOR =
      new CloudGatewayDescriptor(
          "gateway-v1",
          "model-provider",
          "model-v1",
          "consent-v1",
          CloudTransferMode.EXTERNAL_MEMO_CONTENT);

  @Test
  void keepsFakeNoNetworkEvidenceNotApplicableForEveryExecutionState() {
    assertThat(AnalysisService.modelEvidenceStatus(FAKE_DESCRIPTOR, timeoutStarted()))
        .isEqualTo("NOT_APPLICABLE");
    assertThat(AnalysisService.modelEvidenceStatus(FAKE_DESCRIPTOR, timeoutUnknown()))
        .isEqualTo("NOT_APPLICABLE");
    assertThat(
            AnalysisService.modelEvidenceStatus(
                FAKE_DESCRIPTOR, CloudGatewayAttemptObservation.unexpectedNotStarted()))
        .isEqualTo("NOT_APPLICABLE");
  }

  @Test
  void keepsUnknownModelExecutionEvidenceUnknown() {
    assertThat(AnalysisService.modelEvidenceStatus(MODEL_DESCRIPTOR, timeoutUnknown()))
        .isEqualTo("UNKNOWN");
  }

  @Test
  void distinguishesDefiniteNonStartAndObservedModelResult() {
    CloudGatewayAttemptObservation rejected =
        new CloudGatewayAttemptObservation(
            CloudGatewayAttemptTermination.EXECUTOR_REJECTED,
            CloudGatewayExecutionState.NOT_STARTED,
            1,
            CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE));
    CloudGatewayAttemptObservation result =
        new CloudGatewayAttemptObservation(
            CloudGatewayAttemptTermination.GATEWAY_RESULT,
            CloudGatewayExecutionState.STARTED,
            1,
            CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR));

    assertThat(AnalysisService.modelEvidenceStatus(MODEL_DESCRIPTOR, rejected))
        .isEqualTo("NOT_APPLICABLE");
    assertThat(AnalysisService.modelEvidenceStatus(MODEL_DESCRIPTOR, result))
        .isEqualTo("NOT_REPORTED");
    assertThat(AnalysisService.modelEvidenceStatus(MODEL_DESCRIPTOR, timeoutStarted()))
        .isEqualTo("UNKNOWN");
  }

  private CloudGatewayAttemptObservation timeoutStarted() {
    return new CloudGatewayAttemptObservation(
        CloudGatewayAttemptTermination.TIMEOUT,
        CloudGatewayExecutionState.STARTED,
        1,
        CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT));
  }

  private CloudGatewayAttemptObservation timeoutUnknown() {
    return new CloudGatewayAttemptObservation(
        CloudGatewayAttemptTermination.TIMEOUT,
        CloudGatewayExecutionState.UNKNOWN,
        1,
        CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT));
  }
}
