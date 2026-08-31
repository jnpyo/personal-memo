package local.personalmemo.analysis.api;

import java.time.Instant;

public final class AnalysisModelEvidenceDtos {
  private AnalysisModelEvidenceDtos() {}

  public record Summary(
      String schemaVersion,
      String aggregationPolicyVersion,
      Cohort cohort,
      RunCounters runs,
      LocalDecisionEvidenceCounters localDecisionEvidence,
      LifecycleCounters lifecycle,
      DispatchRouteCounters dispatchRoutes,
      InvocationModeCounters invocationModes,
      InvocationReasonCounters invocationReasons,
      LocalModelContributionCounters localModelContributions,
      ApprovedCorrectionSnapshotCounters approvedCorrectionSnapshots,
      FallbackReasonCounters fallbackReasons,
      ChangedFieldCounters changedFields) {}

  public record Cohort(
      String basis, int days, Instant fromInclusive, Instant toExclusive, int maxRuns) {}

  public record RunCounters(int total, int withDispatch, int withoutDispatch) {}

  public record LocalDecisionEvidenceCounters(int current, int legacy) {}

  public record LifecycleCounters(int prepared, int running, int finalized) {}

  public record DispatchRouteCounters(
      int localModel, int externalMemoTransfer, int builtInFake, int legacyOrOther) {}

  public record InvocationModeCounters(int legacyUnknown, int uncertaintyOnly, int aiPreferred) {}

  public record InvocationReasonCounters(
      int legacyUnknown, int semanticUncertainty, int aiPreferredPolicy) {}

  public record LocalModelContributionCounters(
      int notRecorded,
      int pending,
      int acceptedChanged,
      int acceptedUnchanged,
      int localFallback) {}

  public record ApprovedCorrectionSnapshotCounters(int withSignals, int totalSignals) {}

  public record FallbackReasonCounters(
      int defaultRecordFallback,
      int unparsedTemporalCue,
      int unrecognizedActionCue,
      int lowTypeMargin,
      int tagUncertainty,
      int dateUncertainty,
      int unresolvedReference,
      int incompleteTask,
      int multiIntent,
      int candidateLimit,
      int localConflict) {}

  public record ChangedFieldCounters(
      int suggestedTitle,
      int typeCandidates,
      int dateCandidates,
      int tagCandidates,
      int itemCandidates,
      int relationCandidates,
      int ambiguityReasons) {}
}
