package local.personalmemo.analysis.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.ApprovedCorrectionSnapshotCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.ChangedFieldCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.Cohort;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.DispatchRouteCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.FallbackReasonCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.InvocationModeCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.InvocationReasonCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.LifecycleCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.LocalDecisionEvidenceCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.LocalModelContributionCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.RunCounters;
import local.personalmemo.analysis.api.AnalysisModelEvidenceDtos.Summary;
import local.personalmemo.analysis.domain.AnalysisProposalChangedField;
import local.personalmemo.analysis.domain.FallbackReasonCode;
import local.personalmemo.analysis.infrastructure.AnalysisModelEvidenceRepository;
import local.personalmemo.analysis.infrastructure.AnalysisModelEvidenceRepository.ModelEvidenceJdbcRow;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisModelEvidenceService {
  static final int MAX_RUNS = 1000;
  private static final int MAX_DAYS = 90;
  private static final String SCHEMA_VERSION = "1";
  private static final String AGGREGATION_POLICY_VERSION = "analysis-path-evidence-summary-v1";
  private static final String COHORT_BASIS = "ANALYSIS_RUN_CREATED_AT";
  private static final String CURRENT_LOCAL_EVIDENCE_VERSION = "local-decision-v1";
  private static final String LEGACY_LOCAL_EVIDENCE_VERSION = "none";
  private static final String CURRENT_FALLBACK_POLICY_VERSION = "model-fallback-v1";
  private static final String LEGACY_FALLBACK_POLICY_VERSION = "legacy-v0";
  private static final String CURRENT_INVOCATION_POLICY_VERSION = "model-invocation-v1";
  private static final String LEGACY_INVOCATION_POLICY_VERSION = "legacy-v0";
  private static final String CURRENT_APPROVED_CONTEXT_VERSION = "approved-type-anchor-k3-v1";
  private static final String NO_APPROVED_CONTEXT_VERSION = "none";

  private final AnalysisModelEvidenceRepository repository;
  private final CurrentIdentity identity;

  public AnalysisModelEvidenceService(
      AnalysisModelEvidenceRepository repository, CurrentIdentity identity) {
    this.repository = repository;
    this.identity = identity;
  }

  @Transactional(readOnly = true)
  public Summary summary(int days) {
    if (days < 1 || days > MAX_DAYS) {
      throw DomainException.invalid(
          "INVALID_ANALYSIS_PATH_EVIDENCE_WINDOW", "days must be between 1 and 90.");
    }

    Instant toExclusive = Instant.now();
    Instant fromInclusive = toExclusive.minus(Duration.ofDays(days));
    List<ModelEvidenceJdbcRow> rows =
        repository.findRunCohort(identity.ownerId(), fromInclusive, toExclusive, MAX_RUNS + 1);
    if (rows.size() > MAX_RUNS) {
      throw DomainException.invalid(
          "ANALYSIS_PATH_EVIDENCE_WINDOW_TOO_LARGE",
          "The selected window contains more than 1000 analysis runs.");
    }

    MutableCounters counters = new MutableCounters();
    rows.forEach(counters::add);
    counters.validateAggregateInvariants();
    return counters.snapshot(days, fromInclusive, toExclusive);
  }

  private static final class MutableCounters {
    private int totalRuns;
    private int withDispatch;
    private int withoutDispatch;
    private int currentLocalDecisionEvidence;
    private int legacyLocalDecisionEvidence;
    private int prepared;
    private int running;
    private int finalized;
    private int localModelRoute;
    private int externalMemoTransferRoute;
    private int builtInFakeRoute;
    private int legacyOrOtherRoute;
    private int legacyUnknownMode;
    private int uncertaintyOnly;
    private int aiPreferred;
    private int legacyUnknownReason;
    private int semanticUncertainty;
    private int aiPreferredPolicy;
    private int notRecorded;
    private int pending;
    private int acceptedChanged;
    private int acceptedUnchanged;
    private int localFallback;
    private int approvedSnapshotsWithSignals;
    private int approvedSnapshotSignals;
    private int defaultRecordFallback;
    private int unparsedTemporalCue;
    private int unrecognizedActionCue;
    private int lowTypeMargin;
    private int tagUncertainty;
    private int dateUncertainty;
    private int unresolvedReference;
    private int incompleteTask;
    private int multiIntent;
    private int candidateLimit;
    private int localConflict;
    private int suggestedTitle;
    private int typeCandidates;
    private int dateCandidates;
    private int tagCandidates;
    private int itemCandidates;
    private int relationCandidates;
    private int ambiguityReasons;

    void add(ModelEvidenceJdbcRow row) {
      if (row == null) {
        failClosed();
      }
      totalRuns++;
      if (!row.hasDispatch()) {
        validateNoDispatch(row);
        withoutDispatch++;
        return;
      }

      withDispatch++;
      validateDispatch(row);
      addLocalDecisionEvidence(row);
      addLifecycle(row.dispatchState());
      addDispatchRoute(row);
      addInvocationMode(row.invocationMode());
      addInvocationReason(row.invocationReasonCode());
      addLocalModelContribution(row);
      addApprovedCorrectionSnapshot(row);
      row.fallbackReasons().forEach(this::addFallbackReason);
      addChangedFields(row);
    }

    private void validateNoDispatch(ModelEvidenceJdbcRow row) {
      if (row.localModelRoute()
          || row.externalMemoTransferRoute()
          || row.builtInFakeRoute()
          || row.legacyOrOtherRoute()
          || row.dispatchState() != null
          || row.localDecisionEvidenceVersion() != null
          || row.fallbackPolicyVersion() != null
          || row.invocationPolicyVersion() != null
          || row.invocationMode() != null
          || row.invocationReasonCode() != null
          || row.modelContributionStatus() != null
          || row.approvedCorrectionContextVersion() != null
          || row.approvedCorrectionContextCount() != null
          || row.fallbackReasonCount() != null
          || row.changedFieldCount() != null
          || !row.fallbackReasons().isEmpty()
          || !row.changedFields().isEmpty()) {
        failClosed();
      }
    }

    private void validateDispatch(ModelEvidenceJdbcRow row) {
      validateDispatchRoute(row);
      requirePresent(row.dispatchState());
      requirePresent(row.localDecisionEvidenceVersion());
      requirePresent(row.fallbackPolicyVersion());
      requirePresent(row.invocationPolicyVersion());
      requirePresent(row.invocationMode());
      requirePresent(row.invocationReasonCode());
      requirePresent(row.modelContributionStatus());
      requirePresent(row.approvedCorrectionContextVersion());
      if (row.approvedCorrectionContextCount() == null
          || row.fallbackReasonCount() == null
          || row.changedFieldCount() == null
          || row.fallbackReasonCount() != row.fallbackReasons().size()
          || row.changedFieldCount() != row.changedFields().size()) {
        failClosed();
      }

      validateInvocationTuple(row);
      validateLocalEvidenceTuple(row);
      if (LEGACY_LOCAL_EVIDENCE_VERSION.equals(row.localDecisionEvidenceVersion())
          && CURRENT_INVOCATION_POLICY_VERSION.equals(row.invocationPolicyVersion())) {
        failClosed();
      }
      validateNonLocalContributionTuple(row);
      validateApprovedCorrectionTuple(row);
    }

    private void validateDispatchRoute(ModelEvidenceJdbcRow row) {
      int routeCount =
          Boolean.compare(row.localModelRoute(), false)
              + Boolean.compare(row.externalMemoTransferRoute(), false)
              + Boolean.compare(row.builtInFakeRoute(), false)
              + Boolean.compare(row.legacyOrOtherRoute(), false);
      if (routeCount != 1
          || (row.localModelRoute() && !row.hasModelVersion())
          || (row.builtInFakeRoute() && row.hasModelVersion())) {
        failClosed();
      }
    }

    private void validateInvocationTuple(ModelEvidenceJdbcRow row) {
      switch (row.invocationPolicyVersion()) {
        case LEGACY_INVOCATION_POLICY_VERSION -> {
          if (!"LEGACY_UNKNOWN".equals(row.invocationMode())
              || !"LEGACY_UNKNOWN".equals(row.invocationReasonCode())) {
            failClosed();
          }
        }
        case CURRENT_INVOCATION_POLICY_VERSION -> {
          boolean uncertaintyOnlyTuple =
              "UNCERTAINTY_ONLY".equals(row.invocationMode())
                  && "SEMANTIC_UNCERTAINTY".equals(row.invocationReasonCode());
          boolean aiPreferredTuple =
              "AI_PREFERRED".equals(row.invocationMode())
                  && ("SEMANTIC_UNCERTAINTY".equals(row.invocationReasonCode())
                      || "AI_PREFERRED_POLICY".equals(row.invocationReasonCode()));
          if (!uncertaintyOnlyTuple && !aiPreferredTuple) {
            failClosed();
          }
        }
        default -> failClosed();
      }
    }

    private void validateLocalEvidenceTuple(ModelEvidenceJdbcRow row) {
      switch (row.localDecisionEvidenceVersion()) {
        case LEGACY_LOCAL_EVIDENCE_VERSION -> {
          if (!LEGACY_FALLBACK_POLICY_VERSION.equals(row.fallbackPolicyVersion())
              || row.fallbackReasonCount() != 0
              || !"NOT_RECORDED".equals(row.modelContributionStatus())
              || row.changedFieldCount() != 0) {
            failClosed();
          }
        }
        case CURRENT_LOCAL_EVIDENCE_VERSION -> {
          if (!CURRENT_FALLBACK_POLICY_VERSION.equals(row.fallbackPolicyVersion())) {
            failClosed();
          }
          boolean emptyFallbackAllowed =
              CURRENT_INVOCATION_POLICY_VERSION.equals(row.invocationPolicyVersion())
                  && "AI_PREFERRED".equals(row.invocationMode())
                  && "AI_PREFERRED_POLICY".equals(row.invocationReasonCode());
          if (row.fallbackReasonCount() < (emptyFallbackAllowed ? 0 : 1)
              || row.fallbackReasonCount() > FallbackReasonCode.values().length) {
            failClosed();
          }
          validateContributionLifecycle(row);
        }
        default -> failClosed();
      }
    }

    private void validateContributionLifecycle(ModelEvidenceJdbcRow row) {
      switch (row.dispatchState()) {
        case "PREPARED", "RUNNING" -> {
          if (!"PENDING".equals(row.modelContributionStatus()) || row.changedFieldCount() != 0) {
            failClosed();
          }
        }
        case "FINALIZED" -> {
          switch (row.modelContributionStatus()) {
            case "ACCEPTED_CHANGED" -> {
              if (row.changedFieldCount() < 1
                  || row.changedFieldCount() > AnalysisProposalChangedField.values().length) {
                failClosed();
              }
            }
            case "ACCEPTED_UNCHANGED", "LOCAL_FALLBACK" -> {
              if (row.changedFieldCount() != 0) {
                failClosed();
              }
            }
            default -> failClosed();
          }
        }
        default -> failClosed();
      }
    }

    private void validateNonLocalContributionTuple(ModelEvidenceJdbcRow row) {
      if (!isLocalModelRoute(row)
          && ("ACCEPTED_CHANGED".equals(row.modelContributionStatus())
              || "ACCEPTED_UNCHANGED".equals(row.modelContributionStatus())
              || row.changedFieldCount() != 0)) {
        failClosed();
      }
    }

    private void validateApprovedCorrectionTuple(ModelEvidenceJdbcRow row) {
      int count = row.approvedCorrectionContextCount();
      switch (row.approvedCorrectionContextVersion()) {
        case NO_APPROVED_CONTEXT_VERSION -> {
          if (count != 0) {
            failClosed();
          }
        }
        case CURRENT_APPROVED_CONTEXT_VERSION -> {
          if (!CURRENT_LOCAL_EVIDENCE_VERSION.equals(row.localDecisionEvidenceVersion())
              || !CURRENT_FALLBACK_POLICY_VERSION.equals(row.fallbackPolicyVersion())
              || !CURRENT_INVOCATION_POLICY_VERSION.equals(row.invocationPolicyVersion())
              || !"AI_PREFERRED".equals(row.invocationMode())
              || count < 0
              || count > 3) {
            failClosed();
          }
        }
        default -> failClosed();
      }
    }

    private void addLocalDecisionEvidence(ModelEvidenceJdbcRow row) {
      if (CURRENT_LOCAL_EVIDENCE_VERSION.equals(row.localDecisionEvidenceVersion())) {
        currentLocalDecisionEvidence++;
      } else if (LEGACY_LOCAL_EVIDENCE_VERSION.equals(row.localDecisionEvidenceVersion())) {
        legacyLocalDecisionEvidence++;
      } else {
        failClosed();
      }
    }

    private void addLifecycle(String state) {
      switch (state) {
        case "PREPARED" -> prepared++;
        case "RUNNING" -> running++;
        case "FINALIZED" -> finalized++;
        default -> failClosed();
      }
    }

    private void addDispatchRoute(ModelEvidenceJdbcRow row) {
      if (row.localModelRoute()) {
        localModelRoute++;
      } else if (row.externalMemoTransferRoute()) {
        externalMemoTransferRoute++;
      } else if (row.builtInFakeRoute()) {
        builtInFakeRoute++;
      } else if (row.legacyOrOtherRoute()) {
        legacyOrOtherRoute++;
      } else {
        failClosed();
      }
    }

    private void addInvocationMode(String mode) {
      switch (mode) {
        case "LEGACY_UNKNOWN" -> legacyUnknownMode++;
        case "UNCERTAINTY_ONLY" -> uncertaintyOnly++;
        case "AI_PREFERRED" -> aiPreferred++;
        default -> failClosed();
      }
    }

    private void addInvocationReason(String reason) {
      switch (reason) {
        case "LEGACY_UNKNOWN" -> legacyUnknownReason++;
        case "SEMANTIC_UNCERTAINTY" -> semanticUncertainty++;
        case "AI_PREFERRED_POLICY" -> aiPreferredPolicy++;
        default -> failClosed();
      }
    }

    private void addLocalModelContribution(ModelEvidenceJdbcRow row) {
      if (!isLocalModelRoute(row)) {
        return;
      }
      switch (row.modelContributionStatus()) {
        case "NOT_RECORDED" -> notRecorded++;
        case "PENDING" -> pending++;
        case "ACCEPTED_CHANGED" -> acceptedChanged++;
        case "ACCEPTED_UNCHANGED" -> acceptedUnchanged++;
        case "LOCAL_FALLBACK" -> localFallback++;
        default -> failClosed();
      }
    }

    private void addApprovedCorrectionSnapshot(ModelEvidenceJdbcRow row) {
      int signalCount = row.approvedCorrectionContextCount();
      if (signalCount > 0) {
        approvedSnapshotsWithSignals++;
        approvedSnapshotSignals += signalCount;
      }
    }

    private void addChangedFields(ModelEvidenceJdbcRow row) {
      if (!isLocalModelRoute(row)) {
        return;
      }
      if (!"ACCEPTED_CHANGED".equals(row.modelContributionStatus())) {
        if (!row.changedFields().isEmpty()) {
          failClosed();
        }
        return;
      }
      row.changedFields().forEach(this::addChangedField);
    }

    private void addFallbackReason(FallbackReasonCode reason) {
      switch (reason) {
        case DEFAULT_RECORD_FALLBACK -> defaultRecordFallback++;
        case UNPARSED_TEMPORAL_CUE -> unparsedTemporalCue++;
        case UNRECOGNIZED_ACTION_CUE -> unrecognizedActionCue++;
        case LOW_TYPE_MARGIN -> lowTypeMargin++;
        case TAG_UNCERTAINTY -> tagUncertainty++;
        case DATE_UNCERTAINTY -> dateUncertainty++;
        case UNRESOLVED_REFERENCE -> unresolvedReference++;
        case INCOMPLETE_TASK -> incompleteTask++;
        case MULTI_INTENT -> multiIntent++;
        case CANDIDATE_LIMIT -> candidateLimit++;
        case LOCAL_CONFLICT -> localConflict++;
      }
    }

    private void addChangedField(AnalysisProposalChangedField field) {
      switch (field) {
        case SUGGESTED_TITLE -> suggestedTitle++;
        case TYPE_CANDIDATES -> typeCandidates++;
        case DATE_CANDIDATES -> dateCandidates++;
        case TAG_CANDIDATES -> tagCandidates++;
        case ITEM_CANDIDATES -> itemCandidates++;
        case RELATION_CANDIDATES -> relationCandidates++;
        case AMBIGUITY_REASONS -> ambiguityReasons++;
      }
    }

    void validateAggregateInvariants() {
      if (withDispatch + withoutDispatch != totalRuns
          || currentLocalDecisionEvidence + legacyLocalDecisionEvidence != withDispatch
          || prepared + running + finalized != withDispatch
          || localModelRoute + externalMemoTransferRoute + builtInFakeRoute + legacyOrOtherRoute
              != withDispatch
          || legacyUnknownMode + uncertaintyOnly + aiPreferred != withDispatch
          || legacyUnknownReason + semanticUncertainty + aiPreferredPolicy != withDispatch
          || legacyLocalDecisionEvidence > legacyUnknownMode
          || legacyUnknownMode != legacyUnknownReason
          || notRecorded + pending + acceptedChanged + acceptedUnchanged + localFallback
              != localModelRoute
          || notRecorded > legacyLocalDecisionEvidence
          || pending + acceptedChanged + acceptedUnchanged + localFallback
              > currentLocalDecisionEvidence
          || pending > prepared + running
          || acceptedChanged + acceptedUnchanged + localFallback > finalized
          || semanticUncertainty < uncertaintyOnly
          || aiPreferredPolicy > aiPreferred
          || approvedSnapshotsWithSignals > currentLocalDecisionEvidence
          || approvedSnapshotsWithSignals > aiPreferred
          || approvedSnapshotSignals < approvedSnapshotsWithSignals
          || approvedSnapshotSignals > approvedSnapshotsWithSignals * 3
          || totalFallbackReasonCount() < currentLocalDecisionEvidence - aiPreferredPolicy
          || maxFallbackReasonCounter() > currentLocalDecisionEvidence
          || maxChangedFieldCounter() > acceptedChanged) {
        failClosed();
      }
    }

    private int maxFallbackReasonCounter() {
      return Math.max(
          Math.max(
              Math.max(defaultRecordFallback, unparsedTemporalCue),
              Math.max(unrecognizedActionCue, lowTypeMargin)),
          Math.max(
              Math.max(Math.max(tagUncertainty, dateUncertainty), unresolvedReference),
              Math.max(
                  Math.max(incompleteTask, multiIntent), Math.max(candidateLimit, localConflict))));
    }

    private int totalFallbackReasonCount() {
      return defaultRecordFallback
          + unparsedTemporalCue
          + unrecognizedActionCue
          + lowTypeMargin
          + tagUncertainty
          + dateUncertainty
          + unresolvedReference
          + incompleteTask
          + multiIntent
          + candidateLimit
          + localConflict;
    }

    private int maxChangedFieldCounter() {
      return Math.max(
          Math.max(
              Math.max(suggestedTitle, typeCandidates), Math.max(dateCandidates, tagCandidates)),
          Math.max(Math.max(itemCandidates, relationCandidates), ambiguityReasons));
    }

    Summary snapshot(int days, Instant fromInclusive, Instant toExclusive) {
      return new Summary(
          SCHEMA_VERSION,
          AGGREGATION_POLICY_VERSION,
          new Cohort(COHORT_BASIS, days, fromInclusive, toExclusive, MAX_RUNS),
          new RunCounters(totalRuns, withDispatch, withoutDispatch),
          new LocalDecisionEvidenceCounters(
              currentLocalDecisionEvidence, legacyLocalDecisionEvidence),
          new LifecycleCounters(prepared, running, finalized),
          new DispatchRouteCounters(
              localModelRoute, externalMemoTransferRoute, builtInFakeRoute, legacyOrOtherRoute),
          new InvocationModeCounters(legacyUnknownMode, uncertaintyOnly, aiPreferred),
          new InvocationReasonCounters(legacyUnknownReason, semanticUncertainty, aiPreferredPolicy),
          new LocalModelContributionCounters(
              notRecorded, pending, acceptedChanged, acceptedUnchanged, localFallback),
          new ApprovedCorrectionSnapshotCounters(
              approvedSnapshotsWithSignals, approvedSnapshotSignals),
          new FallbackReasonCounters(
              defaultRecordFallback,
              unparsedTemporalCue,
              unrecognizedActionCue,
              lowTypeMargin,
              tagUncertainty,
              dateUncertainty,
              unresolvedReference,
              incompleteTask,
              multiIntent,
              candidateLimit,
              localConflict),
          new ChangedFieldCounters(
              suggestedTitle,
              typeCandidates,
              dateCandidates,
              tagCandidates,
              itemCandidates,
              relationCandidates,
              ambiguityReasons));
    }

    private static void requirePresent(String value) {
      if (value == null || value.isBlank()) {
        failClosed();
      }
    }

    private static boolean isLocalModelRoute(ModelEvidenceJdbcRow row) {
      return row.localModelRoute() && row.hasModelVersion();
    }

    private static void failClosed() {
      throw new IllegalStateException("The durable model evidence is invalid.");
    }
  }
}
