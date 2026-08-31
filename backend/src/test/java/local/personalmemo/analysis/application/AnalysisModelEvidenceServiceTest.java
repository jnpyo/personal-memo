package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.domain.AnalysisProposalChangedField;
import local.personalmemo.analysis.domain.FallbackReasonCode;
import local.personalmemo.analysis.infrastructure.AnalysisModelEvidenceRepository;
import local.personalmemo.analysis.infrastructure.AnalysisModelEvidenceRepository.ModelEvidenceJdbcRow;
import local.personalmemo.common.auth.CurrentIdentity;
import org.junit.jupiter.api.Test;

class AnalysisModelEvidenceServiceTest {

  @Test
  void preservesApprovedCorrectionSnapshotEvidenceForANonLocalAiPreferredDispatch() {
    AnalysisModelEvidenceService service =
        serviceReturning(
            new ModelEvidenceJdbcRow(
                true,
                true,
                false,
                true,
                false,
                false,
                "PREPARED",
                "local-decision-v1",
                "model-fallback-v1",
                "model-invocation-v1",
                "AI_PREFERRED",
                "AI_PREFERRED_POLICY",
                "PENDING",
                "approved-type-anchor-k3-v1",
                1,
                0,
                0,
                Set.of(),
                Set.of()));

    var summary = service.summary(14);

    assertThat(summary.dispatchRoutes().externalMemoTransfer()).isEqualTo(1);
    assertThat(summary.dispatchRoutes().localModel()).isZero();
    assertThat(summary.approvedCorrectionSnapshots().withSignals()).isEqualTo(1);
    assertThat(summary.approvedCorrectionSnapshots().totalSignals()).isEqualTo(1);
  }

  @Test
  void rejectsDispatchWithoutExactlyOneServerClassifiedRoute() {
    AnalysisModelEvidenceService service =
        serviceReturning(
            new ModelEvidenceJdbcRow(
                true,
                false,
                false,
                false,
                false,
                false,
                "PREPARED",
                "local-decision-v1",
                "model-fallback-v1",
                "model-invocation-v1",
                "UNCERTAINTY_ONLY",
                "SEMANTIC_UNCERTAINTY",
                "PENDING",
                "none",
                0,
                1,
                0,
                Set.of(FallbackReasonCode.LOW_TYPE_MARGIN),
                Set.of()));

    assertThatThrownBy(() -> service.summary(14))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The durable model evidence is invalid.");
  }

  @Test
  void rejectsDispatchWithMultipleServerClassifiedRoutes() {
    AnalysisModelEvidenceService service =
        serviceReturning(
            new ModelEvidenceJdbcRow(
                true,
                true,
                true,
                true,
                false,
                false,
                "PREPARED",
                "none",
                "legacy-v0",
                "legacy-v0",
                "LEGACY_UNKNOWN",
                "LEGACY_UNKNOWN",
                "NOT_RECORDED",
                "none",
                0,
                0,
                0,
                Set.of(),
                Set.of()));

    assertThatThrownBy(() -> service.summary(14))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The durable model evidence is invalid.");
  }

  @Test
  void rejectsAcceptedChangedEvidenceFromANonLocalModelRoute() {
    AnalysisModelEvidenceService service =
        serviceReturning(
            new ModelEvidenceJdbcRow(
                true,
                true,
                false,
                true,
                false,
                false,
                "FINALIZED",
                "local-decision-v1",
                "model-fallback-v1",
                "model-invocation-v1",
                "UNCERTAINTY_ONLY",
                "SEMANTIC_UNCERTAINTY",
                "ACCEPTED_CHANGED",
                "none",
                0,
                1,
                1,
                Set.of(FallbackReasonCode.LOW_TYPE_MARGIN),
                Set.of(AnalysisProposalChangedField.SUGGESTED_TITLE)));

    assertThatThrownBy(() -> service.summary(14))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The durable model evidence is invalid.");
  }

  @Test
  void rejectsCurrentInvocationEvidenceWithoutCurrentLocalDecisionEvidence() {
    UUID ownerId = UUID.randomUUID();
    AnalysisModelEvidenceRepository repository = mock(AnalysisModelEvidenceRepository.class);
    CurrentIdentity identity = mock(CurrentIdentity.class);
    when(identity.ownerId()).thenReturn(ownerId);
    when(repository.findRunCohort(eq(ownerId), any(Instant.class), any(Instant.class), eq(1001)))
        .thenReturn(
            List.of(
                new ModelEvidenceJdbcRow(
                    true,
                    true,
                    true,
                    false,
                    false,
                    false,
                    "FINALIZED",
                    "none",
                    "legacy-v0",
                    "model-invocation-v1",
                    "AI_PREFERRED",
                    "AI_PREFERRED_POLICY",
                    "NOT_RECORDED",
                    "none",
                    0,
                    0,
                    0,
                    Set.of(),
                    Set.of())));

    AnalysisModelEvidenceService service = new AnalysisModelEvidenceService(repository, identity);

    assertThatThrownBy(() -> service.summary(14))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The durable model evidence is invalid.");
  }

  @Test
  void rejectsUnknownDurableEnumInsteadOfPublishingAPartialAggregate() {
    UUID ownerId = UUID.randomUUID();
    AnalysisModelEvidenceRepository repository = mock(AnalysisModelEvidenceRepository.class);
    CurrentIdentity identity = mock(CurrentIdentity.class);
    when(identity.ownerId()).thenReturn(ownerId);
    when(repository.findRunCohort(eq(ownerId), any(Instant.class), any(Instant.class), eq(1001)))
        .thenReturn(
            List.of(
                new ModelEvidenceJdbcRow(
                    true,
                    true,
                    true,
                    false,
                    false,
                    false,
                    "FUTURE_STATE",
                    "local-decision-v1",
                    "model-fallback-v1",
                    "model-invocation-v1",
                    "UNCERTAINTY_ONLY",
                    "SEMANTIC_UNCERTAINTY",
                    "PENDING",
                    "none",
                    0,
                    1,
                    0,
                    Set.of(local.personalmemo.analysis.domain.FallbackReasonCode.LOW_TYPE_MARGIN),
                    Set.of())));

    AnalysisModelEvidenceService service = new AnalysisModelEvidenceService(repository, identity);

    assertThatThrownBy(() -> service.summary(14))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The durable model evidence is invalid.");
  }

  private AnalysisModelEvidenceService serviceReturning(ModelEvidenceJdbcRow row) {
    UUID ownerId = UUID.randomUUID();
    AnalysisModelEvidenceRepository repository = mock(AnalysisModelEvidenceRepository.class);
    CurrentIdentity identity = mock(CurrentIdentity.class);
    when(identity.ownerId()).thenReturn(ownerId);
    when(repository.findRunCohort(eq(ownerId), any(Instant.class), any(Instant.class), eq(1001)))
        .thenReturn(List.of(row));
    return new AnalysisModelEvidenceService(repository, identity);
  }
}
