package local.personalmemo.analysis.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import local.personalmemo.analysis.api.AnalysisDtos.ProposalRecoveryView;
import local.personalmemo.analysis.api.AnalysisDtos.ReviewDispositionView;
import local.personalmemo.analysis.api.AnalysisDtos.RunView;
import local.personalmemo.analysis.api.AnalysisDtos.Start;
import local.personalmemo.analysis.domain.AmbiguityReason;
import local.personalmemo.analysis.domain.AnalysisProposalChangedField;
import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.domain.AnalysisProposalSemanticDiff;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.domain.AnalysisRoute;
import local.personalmemo.analysis.domain.ApprovedCorrectionContext;
import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisOutcome;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayAttemptTermination;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudGatewayBindingId;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudProviderRequestToken;
import local.personalmemo.analysis.domain.CloudTransferMode;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.domain.FallbackReasonCode;
import local.personalmemo.analysis.domain.LocalAnalyzer;
import local.personalmemo.analysis.domain.LocalDecisionEvidenceProjection;
import local.personalmemo.analysis.domain.LocalDecisionEvidenceProjector;
import local.personalmemo.analysis.domain.LocalModelInput;
import local.personalmemo.analysis.domain.ModelContributionStatus;
import local.personalmemo.analysis.domain.TagRetrievalContext;
import local.personalmemo.analysis.infrastructure.ApprovedCorrectionContextCodec;
import local.personalmemo.analysis.infrastructure.TagRetrievalContextCodec;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.idempotency.IdempotencyService;
import local.personalmemo.common.security.Hashing;
import local.personalmemo.memo.application.MemoService;
import local.personalmemo.memo.domain.MemoSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AnalysisService {
  private static final String START_OPERATION = "ANALYSIS_START";
  private static final String REJECT_OPERATION = "ANALYSIS_REJECT";
  private static final String POSTPONE_OPERATION = "ANALYSIS_POSTPONE";
  private static final String LEGACY_PROPOSAL_SCHEMA_VERSION = "1";
  private static final String CURRENT_PROPOSAL_SCHEMA_VERSION = "3";
  private static final String DURABLE_EXECUTION_CONTRACT_VERSION = "durable-v1";
  private static final String CURRENT_ATTEMPT_HISTORY_VERSION = "gateway-attempt-v1";
  private static final int MAX_GATEWAY_ATTEMPTS = 2;
  private static final Duration DISPATCH_WINDOW = Duration.ofMinutes(5);
  private static final Duration LEASE_GRACE = Duration.ofSeconds(1);
  private static final long COORDINATION_POLL_MILLIS = 25L;
  private static final int MAX_RECOVERY_PROPOSALS = 100;
  private static final int MAX_RECOVERY_DISPATCH_BATCH = 100;
  private static final Set<String> RESERVED_CLOUD_METADATA =
      Set.of(
          "toolCalls",
          "cloudTransferMode",
          "cloudGatewayVersion",
          "cloudProviderId",
          "cloudModelVersion",
          "cloudConsentPolicyVersion",
          "cloudOutcome",
          "cloudExecutionContractVersion",
          "cloudAuthorizationCheckedAt",
          "cloudAcceptedConsentGrantedAt",
          "cloudProviderRequestToken",
          "tagRetrievalContext",
          "retrievalContext",
          "retrievalContextHash",
          "retrievalContextVersion",
          "retrievalContextCandidateCount",
          "approvedCorrectionHints",
          "approvedCorrectionContext",
          "approvedCorrectionContextHash",
          "approvedCorrectionContextVersion",
          "approvedCorrectionContextCount",
          "invocationPolicyVersion",
          "invocationMode",
          "invocationReasonCode",
          "cloudToolCalls",
          "cloudMutationCalls",
          "cloudResolvedFields",
          "receivedRoutingPolicyVersion",
          "receivedRoutingReasons");
  private static final List<String> REQUIRED_PROVIDER_METADATA =
      List.of(
          "analyzerVersion",
          "promptVersion",
          "localModelVersion",
          "embeddingModelVersion",
          "routingPolicyVersion",
          "toolCalls");
  private static final List<String> BOUNDED_LOCAL_PROVIDER_METADATA =
      List.of(
          "deterministicRulesVersion",
          "route",
          "detectedDateCandidateCount",
          "emittedDateCandidateCount",
          "detectedItemCandidateCount",
          "emittedItemCandidateCount",
          "classificationBasis",
          "unparsedTemporalCueCount",
          "unrecognizedActionCueCount");

  private final JdbcClient db;
  private final CurrentIdentity identity;
  private final MemoService memos;
  private final LocalAnalyzer analyzer;
  private final CloudAnalysisGateway cloudGateway;
  private final BoundedCloudGatewayInvoker cloudInvoker;
  private final Duration cloudAttemptTimeout;
  private final DeterministicAmbiguityGate ambiguityGate;
  private final AnalysisInvocationPolicy invocationPolicy;
  private final boolean approvedCorrectionsEnabled;
  private final AnalysisProposalSchemaValidator proposalSchemaValidator;
  private final AnalysisProposalValidator proposalValidator;
  private final IdempotencyService idempotency;
  private final OwnerTagContextRetriever tagContextRetriever;
  private final TagRetrievalContextCodec tagContextCodec;
  private final OwnerApprovedCorrectionContextRetriever approvedCorrectionContextRetriever;
  private final ApprovedCorrectionContextCodec approvedCorrectionContextCodec;
  private final LocalDecisionEvidenceProjector localDecisionEvidenceProjector;
  private final AnalysisProposalSemanticDiff proposalSemanticDiff;
  private final ObjectMapper json;
  private final TransactionTemplate transactions;

  public AnalysisService(
      JdbcClient db,
      CurrentIdentity identity,
      MemoService memos,
      LocalAnalyzer analyzer,
      CloudAnalysisGateway cloudGateway,
      BoundedCloudGatewayInvoker cloudInvoker,
      CloudGatewayExecutionProperties cloudExecutionProperties,
      DeterministicAmbiguityGate ambiguityGate,
      AnalysisInvocationPolicy invocationPolicy,
      AnalysisInvocationProperties invocationProperties,
      AnalysisProposalSchemaValidator proposalSchemaValidator,
      AnalysisProposalValidator proposalValidator,
      IdempotencyService idempotency,
      OwnerTagContextRetriever tagContextRetriever,
      TagRetrievalContextCodec tagContextCodec,
      OwnerApprovedCorrectionContextRetriever approvedCorrectionContextRetriever,
      ApprovedCorrectionContextCodec approvedCorrectionContextCodec,
      LocalDecisionEvidenceProjector localDecisionEvidenceProjector,
      AnalysisProposalSemanticDiff proposalSemanticDiff,
      ObjectMapper json,
      PlatformTransactionManager transactionManager) {
    this.db = db;
    this.identity = identity;
    this.memos = memos;
    this.analyzer = analyzer;
    this.cloudGateway = cloudGateway;
    this.cloudInvoker = cloudInvoker;
    this.cloudAttemptTimeout = cloudExecutionProperties.getTimeout();
    this.ambiguityGate = ambiguityGate;
    this.invocationPolicy = invocationPolicy;
    this.approvedCorrectionsEnabled = invocationProperties.isApprovedCorrectionsEnabled();
    this.proposalSchemaValidator = proposalSchemaValidator;
    this.proposalValidator = proposalValidator;
    this.idempotency = idempotency;
    this.tagContextRetriever = tagContextRetriever;
    this.tagContextCodec = tagContextCodec;
    this.approvedCorrectionContextRetriever = approvedCorrectionContextRetriever;
    this.approvedCorrectionContextCodec = approvedCorrectionContextCodec;
    this.localDecisionEvidenceProjector = localDecisionEvidenceProjector;
    this.proposalSemanticDiff = proposalSemanticDiff;
    this.json = json;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public RunView start(UUID memoId, String key, Start request) {
    UUID ownerId = identity.ownerId();
    String requestHash = idempotency.hashRequest(new StartRequest(memoId, request));
    Instant coordinationDeadline = Instant.now().plus(DISPATCH_WINDOW);
    StartDecision decision =
        inTransaction(() -> prepareStart(ownerId, memoId, key, request, requestHash));

    while (true) {
      if (decision instanceof StartCompleted completed) {
        return completed.response();
      }
      if (decision instanceof StartStale) {
        throw staleRevision();
      }
      if (decision instanceof StartNeedsBinding needsBinding) {
        CloudGatewayBinding binding =
            needsBinding.binding() == null ? bindGateway() : needsBinding.binding();
        decision =
            inTransaction(
                () ->
                    claimStart(
                        ownerId,
                        needsBinding.runId(),
                        needsBinding.proposalId(),
                        key,
                        requestHash,
                        binding));
        continue;
      }
      if (decision instanceof StartWaiting waiting) {
        if (!Instant.now().isBefore(coordinationDeadline)) {
          throw DomainException.conflict(
              "ANALYSIS_IN_PROGRESS",
              "The same analysis request is still running. Retry with the same key.");
        }
        pauseBeforeCoordinationRetry();
        decision =
            inTransaction(
                () ->
                    claimStart(
                        ownerId,
                        waiting.runId(),
                        waiting.proposalId(),
                        key,
                        requestHash,
                        waiting.binding()));
        continue;
      }
      if (decision instanceof StartDispatch dispatch) {
        CloudGatewayAttemptObservation observation = invokeForCaller(ownerId, dispatch);
        CloudGatewayAttemptObservation completedObservation = observation;
        decision =
            inTransaction(
                () -> finalizeStart(ownerId, dispatch, completedObservation, key, requestHash));
        continue;
      }
      throw new IllegalStateException("Unknown analysis start decision.");
    }
  }

  /**
   * Recovers a bounded set of durable dispatches without relying on an HTTP security context.
   * Candidate owners and idempotency keys come only from server-owned database rows.
   */
  public int recoverPendingDispatches(int batchSize) {
    if (batchSize < 1 || batchSize > MAX_RECOVERY_DISPATCH_BATCH) {
      throw new IllegalArgumentException("batchSize must be between 1 and 100.");
    }
    int completed = 0;
    for (RecoveryCandidate candidate : findRecoveryCandidates(batchSize)) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
      try {
        if (recoverCandidate(candidate)) {
          completed++;
        }
      } catch (RuntimeException ignored) {
        // A malformed or concurrently changed candidate must not block the bounded scan. The row
        // remains durable for a later caller or recovery cycle, without logging private evidence.
      }
    }
    return completed;
  }

  private List<RecoveryCandidate> findRecoveryCandidates(int batchSize) {
    Instant now = Instant.now();
    return db.sql(
            """
            select d.owner_id,
                   d.analysis_run_id,
                   d.reserved_proposal_id,
                   d.idempotency_key_hash,
                   d.request_hash,
                   i.idempotency_key
              from analysis_run_dispatches d
              join analysis_runs r
                on r.id = d.analysis_run_id
               and r.owner_id = d.owner_id
              join idempotency_records i
                on i.owner_id = d.owner_id
               and i.operation = 'ANALYSIS_START'
               and i.resource_id = d.analysis_run_id
               and i.request_hash = d.request_hash
             where d.state = 'PREPARED'
                or (d.state = 'RUNNING' and d.lease_expires_at <= :now)
             order by coalesce(d.lease_expires_at, d.prepared_at),
                      d.prepared_at,
                      d.analysis_run_id
             limit :batchSize
            """)
        .param("now", Timestamp.from(now))
        .param("batchSize", batchSize)
        .query(
            (resultSet, rowNumber) ->
                new RecoveryCandidate(
                    resultSet.getObject("owner_id", UUID.class),
                    resultSet.getObject("analysis_run_id", UUID.class),
                    resultSet.getObject("reserved_proposal_id", UUID.class),
                    resultSet.getString("idempotency_key_hash"),
                    resultSet.getString("request_hash"),
                    resultSet.getString("idempotency_key")))
        .list();
  }

  private boolean recoverCandidate(RecoveryCandidate candidate) {
    if (!Hashing.sha256(candidate.key()).equals(candidate.idempotencyKeyHash())) {
      throw new IllegalStateException("The durable recovery key failed its integrity check.");
    }
    CloudGatewayBinding binding = bindGateway();
    StartDecision decision =
        inTransaction(
            () ->
                claimStart(
                    candidate.ownerId(),
                    candidate.runId(),
                    candidate.proposalId(),
                    candidate.key(),
                    candidate.requestHash(),
                    binding));
    for (int transition = 0; transition < 3; transition++) {
      if (decision instanceof StartCompleted || decision instanceof StartStale) {
        return true;
      }
      if (decision instanceof StartWaiting) {
        return false;
      }
      if (decision instanceof StartNeedsBinding needsBinding) {
        CloudGatewayBinding nextBinding =
            needsBinding.binding() == null ? bindGateway() : needsBinding.binding();
        decision =
            inTransaction(
                () ->
                    claimStart(
                        candidate.ownerId(),
                        needsBinding.runId(),
                        needsBinding.proposalId(),
                        candidate.key(),
                        candidate.requestHash(),
                        nextBinding));
        continue;
      }
      if (decision instanceof StartDispatch dispatch) {
        CloudGatewayAttemptObservation observation =
            invokeForRecovery(candidate.ownerId(), dispatch);
        if (observation == null) {
          return false;
        }
        CloudGatewayAttemptObservation completedObservation = observation;
        decision =
            inTransaction(
                () ->
                    finalizeStart(
                        candidate.ownerId(),
                        dispatch,
                        completedObservation,
                        candidate.key(),
                        candidate.requestHash()));
        continue;
      }
      throw new IllegalStateException("Unknown durable recovery decision.");
    }
    return false;
  }

  private CloudGatewayAttemptObservation invokeForCaller(UUID ownerId, StartDispatch dispatch) {
    try {
      CloudGatewayAttemptObservation observation =
          cloudInvoker.observe(dispatch.binding(), dispatch.request(), dispatch.callTimeout());
      if (observation.termination() == CloudGatewayAttemptTermination.CALLER_INTERRUPTED) {
        recordInterruptedAttempt(ownerId, dispatch, observation);
        throw DomainException.conflict(
            "ANALYSIS_IN_PROGRESS", "The analysis remains recoverable. Retry with the same key.");
      }
      return observation;
    } catch (IllegalArgumentException exception) {
      return CloudGatewayAttemptObservation.unexpectedNotStarted();
    }
  }

  private CloudGatewayAttemptObservation invokeForRecovery(UUID ownerId, StartDispatch dispatch) {
    try {
      CloudGatewayAttemptObservation observation =
          cloudInvoker.observe(dispatch.binding(), dispatch.request(), dispatch.callTimeout());
      if (observation.termination() == CloudGatewayAttemptTermination.CALLER_INTERRUPTED) {
        recordInterruptedAttempt(ownerId, dispatch, observation);
        return null;
      }
      return observation;
    } catch (IllegalArgumentException exception) {
      return CloudGatewayAttemptObservation.unexpectedNotStarted();
    }
  }

  private StartDecision prepareStart(
      UUID ownerId, UUID memoId, String key, Start request, String requestHash) {
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(ownerId, START_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      RunView response = idempotency.convert(replay.get().response(), RunView.class);
      if ("STALE".equals(response.status())) {
        return new StartStale(response);
      }
      if ("RUNNING".equals(response.status())) {
        return new StartNeedsBinding(response.id(), response.proposalId(), null);
      }
      return new StartCompleted(response);
    }

    if (approvedCorrectionsEnabled) {
      acquireOwnerApplicationLock(ownerId);
    }
    MemoSnapshot memo = memos.getCurrentForUpdate(ownerId, memoId);
    requireActiveCurrentRevision(memo, request.memoRevision());

    UUID runId = UUID.randomUUID();
    UUID proposalId = UUID.randomUUID();
    Instant startedAt = Instant.now();
    String proposalSchemaVersion = requireProposalSchemaVersion();
    AnalysisProvenance provenance = requireAnalysisProvenance();
    String routingPolicyVersion = requireRoutingPolicyVersion();
    ObjectNode localProposal =
        analyzer.analyze(
            memoId,
            request.memoRevision(),
            memo.content(),
            memo.clientRecordedAt(),
            memo.sourceTimeZone());
    canonicalizeProviderMetadata(localProposal, localProposal);
    TagRetrievalContext tagRetrievalContext =
        validatePreparedProposal(
            ownerId, localProposal, memo, proposalSchemaVersion, provenance, routingPolicyVersion);
    List<AmbiguityReason> routingReasons = ambiguityGate.routingSignals(localProposal);
    AnalysisRoute route = ambiguityGate.route(routingReasons);

    if (route == AnalysisRoute.LOCAL_REVIEW
        && invocationPolicy.mode() == AnalysisInvocationMode.UNCERTAINTY_ONLY) {
      return completeStartWithoutDispatch(
          ownerId,
          runId,
          proposalId,
          memo,
          proposalSchemaVersion,
          provenance,
          routingPolicyVersion,
          routingReasons,
          "LOCAL",
          localProposal,
          CloudRunEvidence.notRequired(),
          startedAt,
          key,
          requestHash);
    }

    CloudGatewayBinding binding = bindGateway();
    if (binding == null) {
      CloudRunEvidence evidence =
          CloudRunEvidence.descriptorUnavailable(CloudAnalysisOutcome.UNEXPECTED_FAILURE);
      CloudEnrichment fallback =
          validatedFallback(
              ownerId,
              localProposal,
              routingReasons,
              routingPolicyVersion,
              memo,
              proposalSchemaVersion,
              provenance,
              routingPolicyVersion,
              evidence);
      return completeStartWithoutDispatch(
          ownerId,
          runId,
          proposalId,
          memo,
          proposalSchemaVersion,
          provenance,
          routingPolicyVersion,
          routingReasons,
          "HYBRID",
          fallback.proposal(),
          fallback.evidence(),
          startedAt,
          key,
          requestHash);
    }

    AnalysisInvocationDecision invocationDecision = invocationPolicy.decide(route, binding);
    if (!invocationDecision.shouldInvoke()) {
      return completeStartWithoutDispatch(
          ownerId,
          runId,
          proposalId,
          memo,
          proposalSchemaVersion,
          provenance,
          routingPolicyVersion,
          routingReasons,
          "LOCAL",
          localProposal,
          CloudRunEvidence.notRequired(),
          startedAt,
          key,
          requestHash);
    }

    CloudGatewayDescriptor descriptor = binding.descriptor();
    Optional<Instant> authorizationCheckedAt = Optional.empty();
    Optional<Instant> acceptedConsentGrantedAt = Optional.empty();
    if (descriptor.transferMode() == CloudTransferMode.EXTERNAL_MEMO_CONTENT) {
      Instant checkedAt = Instant.now();
      authorizationCheckedAt = Optional.of(checkedAt);
      acceptedConsentGrantedAt =
          acceptedPinnedCloudConsent(ownerId, descriptor.consentPolicyVersion(), checkedAt);
      if (acceptedConsentGrantedAt.isEmpty()) {
        CloudRunEvidence evidence = CloudRunEvidence.consentRequired(descriptor, checkedAt);
        CloudEnrichment fallback =
            validatedFallback(
                ownerId,
                localProposal,
                routingReasons,
                routingPolicyVersion,
                memo,
                proposalSchemaVersion,
                provenance,
                routingPolicyVersion,
                evidence);
        return completeStartWithoutDispatch(
            ownerId,
            runId,
            proposalId,
            memo,
            proposalSchemaVersion,
            provenance,
            routingPolicyVersion,
            routingReasons,
            "HYBRID",
            fallback.proposal(),
            fallback.evidence(),
            startedAt,
            key,
            requestHash);
      }
    }

    CloudProviderRequestToken providerRequestToken =
        CloudProviderRequestToken.issue(ownerId, START_OPERATION, key, requestHash);
    ApprovedCorrectionContext approvedCorrectionContext =
        approvedCorrectionsEnabled
            ? approvedCorrectionContextRetriever.retrieve(ownerId, memo.id(), memo.content())
            : null;
    CloudRunEvidence pendingEvidence =
        CloudRunEvidence.pending(
            descriptor, authorizationCheckedAt, acceptedConsentGrantedAt, providerRequestToken);
    insertRun(
        ownerId,
        runId,
        memo,
        "HYBRID",
        "QUEUED",
        proposalSchemaVersion,
        provenance,
        routingPolicyVersion,
        routingReasons,
        pendingEvidence,
        startedAt,
        null);
    insertDispatch(
        ownerId,
        runId,
        proposalId,
        key,
        requestHash,
        binding.bindingId(),
        localProposal,
        tagRetrievalContext,
        invocationDecision,
        approvedCorrectionContext,
        startedAt);
    RunView pending = new RunView(runId, memo.id(), memo.currentRevision(), "RUNNING", proposalId);
    idempotency.store(ownerId, START_OPERATION, key, requestHash, runId, pending);
    return new StartNeedsBinding(runId, proposalId, binding);
  }

  private StartDecision completeStartWithoutDispatch(
      UUID ownerId,
      UUID runId,
      UUID proposalId,
      MemoSnapshot memo,
      String schemaVersion,
      AnalysisProvenance provenance,
      String routingPolicyVersion,
      List<AmbiguityReason> routingReasons,
      String route,
      ObjectNode proposal,
      CloudRunEvidence evidence,
      Instant startedAt,
      String key,
      String requestHash) {
    Instant completedAt = Instant.now();
    insertRun(
        ownerId,
        runId,
        memo,
        route,
        "REVIEW_REQUIRED",
        schemaVersion,
        provenance,
        routingPolicyVersion,
        routingReasons,
        evidence,
        startedAt,
        completedAt);
    insertProposal(ownerId, proposalId, runId, proposal, completedAt);
    RunView response =
        new RunView(runId, memo.id(), memo.currentRevision(), "REVIEW_REQUIRED", proposalId);
    idempotency.store(ownerId, START_OPERATION, key, requestHash, runId, response);
    return new StartCompleted(response);
  }

  private void insertRun(
      UUID ownerId,
      UUID runId,
      MemoSnapshot memo,
      String route,
      String status,
      String schemaVersion,
      AnalysisProvenance provenance,
      String routingPolicyVersion,
      List<AmbiguityReason> routingReasons,
      CloudRunEvidence cloud,
      Instant createdAt,
      Instant completedAt) {
    db.sql(
            """
            insert into analysis_runs(
              id, owner_id, memo_id, memo_revision, route, status, schema_version,
              analyzer_version, prompt_version, local_model_version, embedding_model_version,
              routing_policy_version, cloud_transfer_mode, cloud_gateway_version,
              cloud_provider_id, cloud_model_version, cloud_consent_policy_version,
              cloud_outcome, cloud_execution_contract_version,
              cloud_authorization_checked_at, cloud_accepted_consent_granted_at,
              cloud_provider_request_token, ambiguity_reasons, created_at, completed_at
            ) values (
              :runId, :ownerId, :memoId, :memoRevision, :route, :status, :schemaVersion,
              :analyzerVersion, :promptVersion, :localModelVersion, :embeddingModelVersion,
              :routingPolicyVersion, :cloudTransferMode, :cloudGatewayVersion,
              :cloudProviderId, :cloudModelVersion, :cloudConsentPolicyVersion,
              :cloudOutcome, :cloudExecutionContractVersion,
              :cloudAuthorizationCheckedAt, :cloudAcceptedConsentGrantedAt,
              :cloudProviderRequestToken, cast(:ambiguityReasons as jsonb), :createdAt, :completedAt
            )
            """)
        .param("runId", runId)
        .param("ownerId", ownerId)
        .param("memoId", memo.id())
        .param("memoRevision", memo.currentRevision())
        .param("route", route)
        .param("status", status)
        .param("schemaVersion", schemaVersion)
        .param("analyzerVersion", provenance.analyzerVersion())
        .param("promptVersion", provenance.promptVersion())
        .param("localModelVersion", provenance.localModelVersion())
        .param("embeddingModelVersion", provenance.embeddingModelVersion())
        .param("routingPolicyVersion", routingPolicyVersion)
        .param("cloudTransferMode", cloud.transferMode())
        .param("cloudGatewayVersion", cloud.gatewayVersion())
        .param("cloudProviderId", cloud.providerId())
        .param("cloudModelVersion", cloud.modelVersion())
        .param("cloudConsentPolicyVersion", cloud.consentPolicyVersion())
        .param("cloudOutcome", cloud.outcome().name())
        .param("cloudExecutionContractVersion", cloud.executionContractVersion())
        .param("cloudAuthorizationCheckedAt", timestampOrNull(cloud.authorizationCheckedAt()))
        .param("cloudAcceptedConsentGrantedAt", timestampOrNull(cloud.acceptedConsentGrantedAt()))
        .param(
            "cloudProviderRequestToken",
            cloud.providerRequestToken() == null ? null : cloud.providerRequestToken().value())
        .param("ambiguityReasons", serializeAmbiguityReasons(routingReasons))
        .param("createdAt", Timestamp.from(createdAt))
        .param("completedAt", timestampOrNull(completedAt))
        .update();
  }

  private void insertProposal(
      UUID ownerId, UUID proposalId, UUID runId, ObjectNode proposal, Instant createdAt) {
    String proposalJson = proposal.toString();
    db.sql(
            """
            insert into analysis_proposals(
              id, owner_id, analysis_run_id, proposal_json, proposal_hash, created_at
            ) values (
              :proposalId, :ownerId, :runId, cast(:proposalJson as jsonb), :proposalHash, :now
            )
            """)
        .param("proposalId", proposalId)
        .param("ownerId", ownerId)
        .param("runId", runId)
        .param("proposalJson", proposalJson)
        .param("proposalHash", Hashing.sha256(proposalJson))
        .param("now", Timestamp.from(createdAt))
        .update();
  }

  private void insertDispatch(
      UUID ownerId,
      UUID runId,
      UUID proposalId,
      String key,
      String requestHash,
      CloudGatewayBindingId bindingId,
      ObjectNode localProposal,
      TagRetrievalContext tagRetrievalContext,
      AnalysisInvocationDecision invocationDecision,
      ApprovedCorrectionContext approvedCorrectionContext,
      Instant preparedAt) {
    if (!invocationDecision.shouldInvoke()) {
      throw new IllegalArgumentException(
          "A durable dispatch requires a model invocation decision.");
    }
    LocalDecisionEvidenceProjection localDecisionEvidence =
        localDecisionEvidenceProjector.project(localProposal);
    String proposalJson = localProposal.toString();
    String retrievalContextJson = tagContextCodec.serialize(tagRetrievalContext);
    String approvedCorrectionContextJson =
        approvedCorrectionContext == null
            ? null
            : approvedCorrectionContextCodec.serialize(approvedCorrectionContext);
    long timeoutMillis = cloudAttemptTimeout.toMillis();
    db.sql(
            """
            insert into analysis_run_dispatches(
              analysis_run_id, owner_id, reserved_proposal_id, idempotency_key_hash,
              request_hash, validated_local_proposal, validated_local_proposal_hash,
              retrieval_context, retrieval_context_hash, retrieval_context_version,
              retrieval_context_candidate_count,
              local_decision_evidence_version, local_decision_evidence,
              fallback_policy_version, fallback_reason_codes,
              model_contribution_status, model_changed_fields,
              invocation_policy_version, invocation_mode, invocation_reason_code,
              approved_correction_context, approved_correction_context_hash,
              approved_correction_context_version, approved_correction_context_count,
              executor_binding_id, call_timeout_ms, max_attempts, deadline_at,
              attempt_history_version, state,
              fence_token, prepared_at, updated_at
            ) values (
              :runId, :ownerId, :proposalId, :idempotencyKeyHash,
              :requestHash, :localProposal, :localProposalHash,
              :retrievalContext, :retrievalContextHash, :retrievalContextVersion,
              :retrievalContextCandidateCount,
              :localDecisionEvidenceVersion, cast(:localDecisionEvidence as jsonb),
              :fallbackPolicyVersion, cast(:fallbackReasonCodes as jsonb),
              'PENDING', cast('[]' as jsonb),
              :invocationPolicyVersion, :invocationMode, :invocationReasonCode,
              :approvedCorrectionContext, :approvedCorrectionContextHash,
              :approvedCorrectionContextVersion, :approvedCorrectionContextCount,
              :bindingId, :callTimeoutMs, :maxAttempts, :deadlineAt,
              :attemptHistoryVersion, 'PREPARED',
              0, :preparedAt, :preparedAt
            )
            """)
        .param("runId", runId)
        .param("ownerId", ownerId)
        .param("proposalId", proposalId)
        .param("idempotencyKeyHash", Hashing.sha256(key))
        .param("requestHash", requestHash)
        .param("localProposal", proposalJson)
        .param("localProposalHash", Hashing.sha256(proposalJson))
        .param("retrievalContext", retrievalContextJson)
        .param("retrievalContextHash", Hashing.sha256(retrievalContextJson))
        .param("retrievalContextVersion", tagRetrievalContext.version())
        .param("retrievalContextCandidateCount", tagRetrievalContext.candidateCount())
        .param("localDecisionEvidenceVersion", LocalDecisionEvidenceProjection.EVIDENCE_VERSION)
        .param("localDecisionEvidence", localDecisionEvidence.evidence().toString())
        .param("fallbackPolicyVersion", LocalDecisionEvidenceProjection.FALLBACK_POLICY_VERSION)
        .param(
            "fallbackReasonCodes", serializeEnumNames(localDecisionEvidence.fallbackReasonCodes()))
        .param("invocationPolicyVersion", invocationDecision.policyVersion())
        .param("invocationMode", invocationDecision.mode().name())
        .param("invocationReasonCode", invocationDecision.reason().name())
        .param("approvedCorrectionContext", approvedCorrectionContextJson)
        .param(
            "approvedCorrectionContextHash",
            approvedCorrectionContextJson == null
                ? null
                : Hashing.sha256(approvedCorrectionContextJson))
        .param(
            "approvedCorrectionContextVersion",
            approvedCorrectionContext == null ? "none" : approvedCorrectionContext.version())
        .param(
            "approvedCorrectionContextCount",
            approvedCorrectionContext == null ? 0 : approvedCorrectionContext.signalCount())
        .param("bindingId", bindingId.value())
        .param("callTimeoutMs", timeoutMillis)
        .param("maxAttempts", MAX_GATEWAY_ATTEMPTS)
        .param("deadlineAt", Timestamp.from(preparedAt.plus(DISPATCH_WINDOW)))
        .param("attemptHistoryVersion", CURRENT_ATTEMPT_HISTORY_VERSION)
        .param("preparedAt", Timestamp.from(preparedAt))
        .update();
  }

  private StartDecision claimStart(
      UUID ownerId,
      UUID runId,
      UUID proposalId,
      String key,
      String requestHash,
      CloudGatewayBinding binding) {
    RunView replay = requireStartReplay(ownerId, key, requestHash);
    if (!"RUNNING".equals(replay.status())) {
      return decisionFromFinalResponse(replay);
    }

    DispatchIdentity observed = findDispatchIdentity(ownerId, runId, proposalId);
    MemoSnapshot memo = memos.getCurrentForUpdate(ownerId, observed.memoId());
    DispatchSnapshot dispatch = findDispatch(ownerId, runId, proposalId, true);
    requireSameDispatchIdentity(observed, dispatch);

    if (dispatch.finalizedAt() != null || "FINALIZED".equals(dispatch.dispatchState())) {
      return decisionFromFinalResponse(requireStartReplay(ownerId, key, requestHash));
    }
    Instant now = Instant.now();
    if ("STALE".equals(dispatch.status())
        || !memo.isActive()
        || memo.currentRevision() != dispatch.memoRevision()) {
      supersedeUnobservedAttemptIfTracked(ownerId, dispatch, now);
      return completeDurableDispatch(
          ownerId,
          dispatch,
          stampedStaleProposal(
              dispatch.localProposal(),
              dispatch.routingReasons(),
              dispatch.routingPolicyVersion(),
              dispatch.evidence().withOutcome(CloudAnalysisOutcome.CANCELLED_STALE)),
          dispatch.evidence().withOutcome(CloudAnalysisOutcome.CANCELLED_STALE),
          "STALE",
          key,
          requestHash,
          now);
    }

    if ("RUNNING".equals(dispatch.dispatchState())
        && dispatch.leaseExpiresAt() != null
        && dispatch.leaseExpiresAt().isAfter(now)) {
      return new StartWaiting(runId, proposalId, binding);
    }
    Duration remainingDispatchWindow = Duration.between(now, dispatch.deadlineAt());
    if (remainingDispatchWindow.compareTo(Duration.ofMillis(1)) < 0
        || dispatch.fenceToken() >= dispatch.maxAttempts()) {
      supersedeUnobservedAttemptIfTracked(ownerId, dispatch, now);
      return completeFallbackBeforeCall(
          ownerId,
          dispatch,
          memo,
          dispatch.evidence().withOutcome(CloudAnalysisOutcome.RETRY_EXHAUSTED),
          key,
          requestHash,
          now);
    }
    if (!matchesBinding(dispatch, binding)) {
      supersedeUnobservedAttemptIfTracked(ownerId, dispatch, now);
      return completeFallbackBeforeCall(
          ownerId,
          dispatch,
          memo,
          dispatch.evidence().withOutcome(CloudAnalysisOutcome.UNEXPECTED_FAILURE),
          key,
          requestHash,
          now);
    }
    if (!dispatch.invocationEvidence().compatibleWith(dispatch.descriptor())
        || !dispatch.approvedCorrectionContext().valid()) {
      supersedeUnobservedAttemptIfTracked(ownerId, dispatch, now);
      return completeFallbackBeforeCall(
          ownerId,
          dispatch,
          memo,
          dispatch.evidence().withOutcome(CloudAnalysisOutcome.UNEXPECTED_FAILURE),
          key,
          requestHash,
          now);
    }

    CloudRunEvidence attemptEvidence = dispatch.evidence();
    if (dispatch.descriptor().transferMode() == CloudTransferMode.EXTERNAL_MEMO_CONTENT) {
      Instant checkedAt = Instant.now();
      Optional<Instant> currentGrant =
          acceptedPinnedCloudConsent(
              ownerId, dispatch.descriptor().consentPolicyVersion(), checkedAt);
      if (currentGrant.isEmpty()
          || dispatch.acceptedConsentGrantedAt() == null
          || !currentGrant.get().equals(dispatch.acceptedConsentGrantedAt())) {
        supersedeUnobservedAttemptIfTracked(ownerId, dispatch, checkedAt);
        return completeFallbackBeforeCall(
            ownerId,
            dispatch,
            memo,
            CloudRunEvidence.durableConsentRequired(dispatch.descriptor(), checkedAt),
            key,
            requestHash,
            checkedAt);
      }
      attemptEvidence = dispatch.evidence().withAuthorization(checkedAt, currentGrant.get());
    }

    Optional<LocalModelInput> localModelInput;
    try {
      localModelInput =
          localModelInputFor(
              dispatch.descriptor(), memo, dispatch.approvedCorrectionContext().context());
    } catch (RuntimeException exception) {
      supersedeUnobservedAttemptIfTracked(ownerId, dispatch, now);
      return completeFallbackBeforeCall(
          ownerId,
          dispatch,
          memo,
          dispatch.evidence().withOutcome(CloudAnalysisOutcome.UNEXPECTED_FAILURE),
          key,
          requestHash,
          now);
    }

    long nextFence = dispatch.fenceToken() + 1;
    Duration persistedAttemptTimeout = Duration.ofMillis(dispatch.callTimeoutMs());
    Duration attemptTimeout =
        persistedAttemptTimeout.compareTo(remainingDispatchWindow) <= 0
            ? persistedAttemptTimeout
            : remainingDispatchWindow;
    Instant proposedLeaseExpiry = now.plus(attemptTimeout).plus(LEASE_GRACE);
    Instant leaseExpiresAt =
        proposedLeaseExpiry.isAfter(dispatch.deadlineAt())
            ? dispatch.deadlineAt()
            : proposedLeaseExpiry;
    if (CURRENT_ATTEMPT_HISTORY_VERSION.equals(dispatch.attemptHistoryVersion())) {
      supersedeUnobservedAttempt(ownerId, runId, now);
    }
    db.sql(
            """
            update analysis_run_dispatches
               set state = 'RUNNING',
                   fence_token = :fenceToken,
                   last_attempt_started_at = :startedAt,
                   lease_expires_at = :leaseExpiresAt,
                   updated_at = :startedAt
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("fenceToken", nextFence)
        .param("startedAt", Timestamp.from(now))
        .param("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
        .param("runId", runId)
        .param("ownerId", ownerId)
        .update();
    if (CURRENT_ATTEMPT_HISTORY_VERSION.equals(dispatch.attemptHistoryVersion())) {
      insertAttempt(
          ownerId,
          runId,
          nextFence,
          Math.toIntExact(attemptTimeout.toMillis()),
          now,
          leaseExpiresAt);
    }
    updateRunForAttempt(ownerId, runId, attemptEvidence);

    CloudAnalysisRequest request =
        new CloudAnalysisRequest(
            dispatch.localProposal(),
            dispatch.routingReasons(),
            dispatch.routingPolicyVersion(),
            dispatch.descriptor(),
            Optional.ofNullable(attemptEvidence.authorizationCheckedAt()),
            Optional.ofNullable(attemptEvidence.acceptedConsentGrantedAt()),
            attemptEvidence.providerRequestToken(),
            dispatch.tagRetrievalContext(),
            localModelInput);
    return new StartDispatch(
        runId,
        proposalId,
        nextFence,
        dispatch.attemptHistoryVersion(),
        binding,
        request,
        attemptTimeout);
  }

  private Optional<LocalModelInput> localModelInputFor(
      CloudGatewayDescriptor descriptor,
      MemoSnapshot memo,
      Optional<ApprovedCorrectionContext> approvedCorrectionContext) {
    if (descriptor.transferMode() != CloudTransferMode.LOCAL_MACHINE_MEMO_CONTENT) {
      return Optional.empty();
    }
    List<ApprovedCorrectionContext.Hint> hints =
        approvedCorrectionContext
            .map(context -> context.rehydrate(memo.content()))
            .orElseGet(List::of);
    return Optional.of(
        new LocalModelInput(memo.content(), memo.clientRecordedAt(), memo.sourceTimeZone(), hints));
  }

  private StartDecision finalizeStart(
      UUID ownerId,
      StartDispatch attempt,
      CloudGatewayAttemptObservation observation,
      String key,
      String requestHash) {
    Instant observedAt = Instant.now();
    RunView replay = requireStartReplay(ownerId, key, requestHash);
    if (!"RUNNING".equals(replay.status())) {
      recordAttemptObservation(ownerId, attempt, observation, "FENCED_OUT", observedAt);
      return decisionFromFinalResponse(replay);
    }

    DispatchIdentity observed =
        findDispatchIdentity(ownerId, attempt.runId(), attempt.proposalId());
    MemoSnapshot memo = memos.getCurrentForUpdate(ownerId, observed.memoId());
    DispatchSnapshot dispatch = findDispatch(ownerId, attempt.runId(), attempt.proposalId(), true);
    requireSameDispatchIdentity(observed, dispatch);
    if (dispatch.finalizedAt() != null || "FINALIZED".equals(dispatch.dispatchState())) {
      recordAttemptObservation(ownerId, attempt, observation, "FENCED_OUT", observedAt);
      return decisionFromFinalResponse(requireStartReplay(ownerId, key, requestHash));
    }
    if (dispatch.fenceToken() != attempt.fenceToken()) {
      recordAttemptObservation(ownerId, attempt, observation, "FENCED_OUT", observedAt);
      return new StartWaiting(attempt.runId(), attempt.proposalId(), attempt.binding());
    }

    CloudAnalysisResult result = observation.effectiveResult();
    Instant completedAt = observedAt;
    CloudRunEvidence resultEvidence = evidenceForResult(attempt.request(), result);
    if ("STALE".equals(dispatch.status())
        || !memo.isActive()
        || memo.currentRevision() != dispatch.memoRevision()) {
      recordAttemptObservation(ownerId, attempt, observation, "STALE_FINALIZE", observedAt);
      return completeDurableDispatch(
          ownerId,
          dispatch,
          stampedStaleProposal(
              dispatch.localProposal(),
              dispatch.routingReasons(),
              dispatch.routingPolicyVersion(),
              resultEvidence),
          resultEvidence,
          "STALE",
          key,
          requestHash,
          completedAt);
    }

    CloudEnrichment enrichment =
        resolveCloudResult(
            ownerId,
            dispatch.localProposal(),
            result,
            attempt.request(),
            memo,
            dispatch.schemaVersion(),
            dispatch.provenance(),
            dispatch.routingPolicyVersion());
    recordAttemptObservation(ownerId, attempt, observation, "APPLIED_TO_RUN", observedAt);
    return completeDurableDispatch(
        ownerId,
        dispatch,
        enrichment.proposal(),
        enrichment.evidence(),
        "REVIEW_REQUIRED",
        key,
        requestHash,
        completedAt);
  }

  private StartDecision completeFallbackBeforeCall(
      UUID ownerId,
      DispatchSnapshot dispatch,
      MemoSnapshot memo,
      CloudRunEvidence evidence,
      String key,
      String requestHash,
      Instant completedAt) {
    CloudEnrichment fallback =
        validatedFallback(
            ownerId,
            dispatch.localProposal(),
            dispatch.routingReasons(),
            dispatch.routingPolicyVersion(),
            memo,
            dispatch.schemaVersion(),
            dispatch.provenance(),
            dispatch.routingPolicyVersion(),
            evidence);
    return completeDurableDispatch(
        ownerId,
        dispatch,
        fallback.proposal(),
        fallback.evidence(),
        "REVIEW_REQUIRED",
        key,
        requestHash,
        completedAt);
  }

  private StartDecision completeDurableDispatch(
      UUID ownerId,
      DispatchSnapshot dispatch,
      ObjectNode proposal,
      CloudRunEvidence evidence,
      String status,
      String key,
      String requestHash,
      Instant completedAt) {
    ModelContribution modelContribution =
        modelContributionFor(dispatch, proposal, evidence, status);
    int updated =
        db.sql(
                """
            update analysis_runs
               set status = :status,
                   cloud_transfer_mode = :cloudTransferMode,
                   cloud_gateway_version = :cloudGatewayVersion,
                   cloud_provider_id = :cloudProviderId,
                   cloud_model_version = :cloudModelVersion,
                   cloud_consent_policy_version = :cloudConsentPolicyVersion,
                   cloud_outcome = :cloudOutcome,
                   cloud_execution_contract_version = :cloudExecutionContractVersion,
                   cloud_authorization_checked_at = :cloudAuthorizationCheckedAt,
                   cloud_accepted_consent_granted_at = :cloudAcceptedConsentGrantedAt,
                   cloud_provider_request_token = :cloudProviderRequestToken,
                   completed_at = :completedAt
             where id = :runId
               and owner_id = :ownerId
            """)
            .param("status", status)
            .param("cloudTransferMode", evidence.transferMode())
            .param("cloudGatewayVersion", evidence.gatewayVersion())
            .param("cloudProviderId", evidence.providerId())
            .param("cloudModelVersion", evidence.modelVersion())
            .param("cloudConsentPolicyVersion", evidence.consentPolicyVersion())
            .param("cloudOutcome", evidence.outcome().name())
            .param("cloudExecutionContractVersion", evidence.executionContractVersion())
            .param(
                "cloudAuthorizationCheckedAt", timestampOrNull(evidence.authorizationCheckedAt()))
            .param(
                "cloudAcceptedConsentGrantedAt",
                timestampOrNull(evidence.acceptedConsentGrantedAt()))
            .param(
                "cloudProviderRequestToken",
                evidence.providerRequestToken() == null
                    ? null
                    : evidence.providerRequestToken().value())
            .param("completedAt", Timestamp.from(completedAt))
            .param("runId", dispatch.runId())
            .param("ownerId", ownerId)
            .update();
    if (updated != 1) {
      throw new IllegalStateException("The durable analysis run is missing during finalization.");
    }
    insertProposal(ownerId, dispatch.proposalId(), dispatch.runId(), proposal, completedAt);
    db.sql(
            """
             update analysis_run_dispatches
                set state = 'FINALIZED',
                    validated_local_proposal = null,
                    retrieval_context = null,
                    approved_correction_context = null,
                    model_contribution_status = :modelContributionStatus,
                    model_changed_fields = cast(:modelChangedFields as jsonb),
                    lease_expires_at = null,
                    finalized_at = :finalizedAt,
                    updated_at = :finalizedAt
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("modelContributionStatus", modelContribution.status().name())
        .param("modelChangedFields", serializeEnumNames(modelContribution.changedFields()))
        .param("finalizedAt", Timestamp.from(completedAt))
        .param("runId", dispatch.runId())
        .param("ownerId", ownerId)
        .update();

    RunView response =
        new RunView(
            dispatch.runId(),
            dispatch.memoId(),
            dispatch.memoRevision(),
            status,
            dispatch.proposalId());
    idempotency.complete(ownerId, START_OPERATION, key, requestHash, dispatch.runId(), response);
    return "STALE".equals(status) ? new StartStale(response) : new StartCompleted(response);
  }

  private ModelContribution modelContributionFor(
      DispatchSnapshot dispatch,
      ObjectNode completedProposal,
      CloudRunEvidence evidence,
      String runStatus) {
    if (!dispatch.localDecisionEvidence().current()) {
      return new ModelContribution(ModelContributionStatus.NOT_RECORDED, List.of());
    }
    if (dispatch.descriptor().transferMode() != CloudTransferMode.LOCAL_MACHINE_MEMO_CONTENT
        || "STALE".equals(runStatus)
        || evidence.outcome() != CloudAnalysisOutcome.SUCCESS) {
      return new ModelContribution(ModelContributionStatus.LOCAL_FALLBACK, List.of());
    }
    List<AnalysisProposalChangedField> changedFields =
        proposalSemanticDiff.changedFields(dispatch.localProposal(), completedProposal);
    return new ModelContribution(
        changedFields.isEmpty()
            ? ModelContributionStatus.ACCEPTED_UNCHANGED
            : ModelContributionStatus.ACCEPTED_CHANGED,
        changedFields);
  }

  private CloudEnrichment resolveCloudResult(
      UUID ownerId,
      ObjectNode localProposal,
      CloudAnalysisResult result,
      CloudAnalysisRequest request,
      MemoSnapshot memo,
      String expectedSchemaVersion,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion) {
    if (result instanceof CloudAnalysisResult.Failure failure) {
      return validatedFallback(
          ownerId,
          localProposal,
          request.routingReasons(),
          request.routingPolicyVersion(),
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion,
          CloudRunEvidence.from(request, outcomeFor(failure.reason())));
    }
    if (!(result instanceof CloudAnalysisResult.Success success)) {
      return validatedFallback(
          ownerId,
          localProposal,
          request.routingReasons(),
          request.routingPolicyVersion(),
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion,
          CloudRunEvidence.from(request, CloudAnalysisOutcome.INVALID_RESPONSE));
    }

    ObjectNode enriched = success.proposal();
    CloudRunEvidence successEvidence = CloudRunEvidence.from(request, CloudAnalysisOutcome.SUCCESS);
    try {
      validateProposal(
          ownerId,
          enriched,
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion);
      canonicalizeProviderMetadata(enriched, request.validatedLocalProposal());
      stampCloudMetadata(
          enriched, request.routingReasons(), request.routingPolicyVersion(), successEvidence);
      validateProposal(
          ownerId,
          enriched,
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion);
      return new CloudEnrichment(enriched, successEvidence);
    } catch (RuntimeException exception) {
      return validatedFallback(
          ownerId,
          localProposal,
          request.routingReasons(),
          request.routingPolicyVersion(),
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion,
          CloudRunEvidence.from(request, CloudAnalysisOutcome.INVALID_RESPONSE));
    }
  }

  private CloudRunEvidence evidenceForResult(
      CloudAnalysisRequest request, CloudAnalysisResult result) {
    if (result instanceof CloudAnalysisResult.Failure failure) {
      return CloudRunEvidence.from(request, outcomeFor(failure.reason()));
    }
    if (result instanceof CloudAnalysisResult.Success) {
      return CloudRunEvidence.from(request, CloudAnalysisOutcome.SUCCESS);
    }
    return CloudRunEvidence.from(request, CloudAnalysisOutcome.INVALID_RESPONSE);
  }

  private ObjectNode stampedStaleProposal(
      ObjectNode localProposal,
      List<AmbiguityReason> routingReasons,
      String routingPolicyVersion,
      CloudRunEvidence evidence) {
    ObjectNode staleProposal = localProposal.deepCopy();
    canonicalizeProviderMetadata(staleProposal, staleProposal);
    stampCloudMetadata(staleProposal, routingReasons, routingPolicyVersion, evidence);
    proposalSchemaValidator.validate(staleProposal);
    return staleProposal;
  }

  private void updateRunForAttempt(UUID ownerId, UUID runId, CloudRunEvidence evidence) {
    db.sql(
            """
            update analysis_runs
               set status = 'RUNNING',
                   cloud_outcome = 'PENDING',
                   cloud_authorization_checked_at = :cloudAuthorizationCheckedAt,
                   cloud_accepted_consent_granted_at = :cloudAcceptedConsentGrantedAt,
                   cloud_provider_request_token = :cloudProviderRequestToken
             where id = :runId
               and owner_id = :ownerId
            """)
        .param("cloudAuthorizationCheckedAt", timestampOrNull(evidence.authorizationCheckedAt()))
        .param(
            "cloudAcceptedConsentGrantedAt", timestampOrNull(evidence.acceptedConsentGrantedAt()))
        .param(
            "cloudProviderRequestToken",
            evidence.providerRequestToken() == null
                ? null
                : evidence.providerRequestToken().value())
        .param("runId", runId)
        .param("ownerId", ownerId)
        .update();
  }

  private void supersedeUnobservedAttempt(UUID ownerId, UUID runId, Instant supersededAt) {
    db.sql(
            """
            update analysis_run_dispatch_attempts
               set attempt_state = 'SUPERSEDED',
                   execution_state = case
                     when local_termination is null then 'UNKNOWN'
                     else execution_state
                   end,
                   local_termination = coalesce(local_termination, 'PROCESS_LOST'),
                   result_state = 'UNKNOWN',
                   gateway_outcome = null,
                   disposition = 'SUPERSEDED',
                   duration_status = case
                     when local_termination is null then 'UNKNOWN'
                     else duration_status
                   end,
                   duration_ms = case
                     when local_termination is null then null
                     else duration_ms
                   end,
                   model_token_status = case
                     when local_termination is null then 'UNKNOWN'
                     else model_token_status
                   end,
                   model_input_tokens = case
                     when local_termination is null then null
                     else model_input_tokens
                   end,
                   model_output_tokens = case
                     when local_termination is null then null
                     else model_output_tokens
                   end,
                   model_total_tokens = case
                     when local_termination is null then null
                     else model_total_tokens
                   end,
                   cost_status = case
                     when local_termination is null then 'UNKNOWN'
                     else cost_status
                   end,
                   cost_amount = case
                     when local_termination is null then null
                     else cost_amount
                   end,
                   cost_currency = case
                     when local_termination is null then null
                     else cost_currency
                   end,
                   updated_at = :supersededAt
             where analysis_run_id = :runId
               and owner_id = :ownerId
               and attempt_state = 'IN_FLIGHT'
            """)
        .param("supersededAt", Timestamp.from(supersededAt))
        .param("runId", runId)
        .param("ownerId", ownerId)
        .update();
  }

  private void supersedeUnobservedAttemptIfTracked(
      UUID ownerId, DispatchSnapshot dispatch, Instant supersededAt) {
    if (CURRENT_ATTEMPT_HISTORY_VERSION.equals(dispatch.attemptHistoryVersion())) {
      supersedeUnobservedAttempt(ownerId, dispatch.runId(), supersededAt);
    }
  }

  private void insertAttempt(
      UUID ownerId,
      UUID runId,
      long fenceToken,
      int effectiveTimeoutMs,
      Instant claimedAt,
      Instant leaseExpiresAt) {
    db.sql(
            """
            insert into analysis_run_dispatch_attempts(
              analysis_run_id, owner_id, attempt_history_version,
              fence_token, effective_timeout_ms,
              attempt_state, execution_state, local_termination, result_state,
              gateway_outcome, disposition, duration_status, duration_ms,
              model_token_status, model_input_tokens, model_output_tokens, model_total_tokens,
              cost_status, cost_amount, cost_currency,
              claimed_at, lease_expires_at, observed_at, updated_at
            ) values (
              :runId, :ownerId, :attemptHistoryVersion,
              :fenceToken, :effectiveTimeoutMs,
              'IN_FLIGHT', 'PENDING', null, 'PENDING',
              null, 'PENDING', 'UNKNOWN', null,
              'PENDING', null, null, null,
              'PENDING', null, null,
              :claimedAt, :leaseExpiresAt, null, :claimedAt
            )
            """)
        .param("runId", runId)
        .param("ownerId", ownerId)
        .param("attemptHistoryVersion", CURRENT_ATTEMPT_HISTORY_VERSION)
        .param("fenceToken", fenceToken)
        .param("effectiveTimeoutMs", effectiveTimeoutMs)
        .param("claimedAt", Timestamp.from(claimedAt))
        .param("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
        .update();
  }

  private void recordAttemptObservation(
      UUID ownerId,
      StartDispatch attempt,
      CloudGatewayAttemptObservation observation,
      String disposition,
      Instant observedAt) {
    String modelEvidenceStatus = modelEvidenceStatus(attempt.request().descriptor(), observation);
    String resultState = observation.gatewayResultObserved() ? "OBSERVED" : "UNKNOWN";
    String gatewayOutcome =
        observation.gatewayResultObserved()
            ? gatewayOutcome(observation.effectiveResult()).name()
            : null;
    String localTermination =
        observation.termination() == CloudGatewayAttemptTermination.GATEWAY_RESULT
            ? "RESULT"
            : observation.termination().name();
    String attemptState =
        switch (disposition) {
          case "RECOVERY_PENDING" -> "IN_FLIGHT";
          case "FENCED_OUT", "SUPERSEDED" -> "SUPERSEDED";
          default -> "OBSERVED";
        };
    int updated =
        db.sql(
                """
                update analysis_run_dispatch_attempts
               set attempt_state = :attemptState,
                   execution_state = :executionState,
                   local_termination = :localTermination,
                   result_state = :resultState,
                   gateway_outcome = :gatewayOutcome,
                   disposition = :disposition,
                   duration_status = 'MEASURED',
                   duration_ms = :durationMs,
                   model_token_status = :modelTokenStatus,
                   model_input_tokens = null,
                   model_output_tokens = null,
                   model_total_tokens = null,
                   cost_status = :costStatus,
                   cost_amount = null,
                   cost_currency = null,
                   observed_at = :observedAt,
                   updated_at = :observedAt
             where analysis_run_id = :runId
               and owner_id = :ownerId
               and fence_token = :fenceToken
               and attempt_state in ('IN_FLIGHT', 'SUPERSEDED')
                """)
            .param("attemptState", attemptState)
            .param("executionState", observation.executionState().name())
            .param("localTermination", localTermination)
            .param("resultState", resultState)
            .param("gatewayOutcome", gatewayOutcome)
            .param("disposition", disposition)
            .param("durationMs", observation.elapsedMillis())
            .param("modelTokenStatus", modelEvidenceStatus)
            .param("costStatus", modelEvidenceStatus)
            .param("observedAt", Timestamp.from(observedAt))
            .param("runId", attempt.runId())
            .param("ownerId", ownerId)
            .param("fenceToken", attempt.fenceToken())
            .update();
    if (CURRENT_ATTEMPT_HISTORY_VERSION.equals(attempt.attemptHistoryVersion()) && updated != 1) {
      throw new IllegalStateException("The durable attempt observation row is missing.");
    }
    if ("none".equals(attempt.attemptHistoryVersion()) && updated != 0) {
      throw new IllegalStateException("A legacy dispatch cannot contain attempt observations.");
    }
  }

  private void recordInterruptedAttempt(
      UUID ownerId, StartDispatch attempt, CloudGatewayAttemptObservation observation) {
    boolean interrupted = Thread.interrupted();
    try {
      inTransaction(
          () -> {
            boolean currentAttempt =
                db.sql(
                        """
                        select fence_token = :fenceToken and state <> 'FINALIZED'
                          from analysis_run_dispatches
                         where analysis_run_id = :runId
                           and owner_id = :ownerId
                           and reserved_proposal_id = :proposalId
                           for update
                        """)
                    .param("fenceToken", attempt.fenceToken())
                    .param("runId", attempt.runId())
                    .param("ownerId", ownerId)
                    .param("proposalId", attempt.proposalId())
                    .query(Boolean.class)
                    .single();
            recordAttemptObservation(
                ownerId,
                attempt,
                observation,
                currentAttempt ? "RECOVERY_PENDING" : "SUPERSEDED",
                Instant.now());
            return Boolean.TRUE;
          });
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static String modelEvidenceStatus(
      CloudGatewayDescriptor descriptor, CloudGatewayAttemptObservation observation) {
    if (descriptor.transferMode() == CloudTransferMode.NO_NETWORK
        && "none".equals(descriptor.modelVersion())) {
      return "NOT_APPLICABLE";
    }
    return switch (observation.executionState()) {
      case NOT_STARTED -> "NOT_APPLICABLE";
      case UNKNOWN -> "UNKNOWN";
      case STARTED -> observation.gatewayResultObserved() ? "NOT_REPORTED" : "UNKNOWN";
    };
  }

  private CloudAnalysisOutcome gatewayOutcome(CloudAnalysisResult result) {
    if (result instanceof CloudAnalysisResult.Success) {
      return CloudAnalysisOutcome.SUCCESS;
    }
    if (result instanceof CloudAnalysisResult.Failure failure) {
      return outcomeFor(failure.reason());
    }
    return CloudAnalysisOutcome.UNEXPECTED_FAILURE;
  }

  private RunView requireStartReplay(UUID ownerId, String key, String requestHash) {
    return idempotency
        .find(ownerId, START_OPERATION, key, requestHash)
        .map(stored -> idempotency.convert(stored.response(), RunView.class))
        .orElseThrow(
            () -> new IllegalStateException("The durable analysis reservation is missing."));
  }

  private StartDecision decisionFromFinalResponse(RunView response) {
    if ("STALE".equals(response.status())) {
      return new StartStale(response);
    }
    if ("RUNNING".equals(response.status())) {
      return new StartNeedsBinding(response.id(), response.proposalId(), null);
    }
    return new StartCompleted(response);
  }

  private DispatchIdentity findDispatchIdentity(UUID ownerId, UUID runId, UUID proposalId) {
    return db.sql(
            """
            select r.id as run_id,
                   r.memo_id,
                   r.memo_revision,
                   d.reserved_proposal_id
              from analysis_runs r
              join analysis_run_dispatches d
                on d.analysis_run_id = r.id
               and d.owner_id = r.owner_id
             where r.id = :runId
               and r.owner_id = :ownerId
               and d.reserved_proposal_id = :proposalId
            """)
        .param("runId", runId)
        .param("ownerId", ownerId)
        .param("proposalId", proposalId)
        .query(
            (resultSet, rowNumber) ->
                new DispatchIdentity(
                    resultSet.getObject("run_id", UUID.class),
                    resultSet.getObject("reserved_proposal_id", UUID.class),
                    resultSet.getObject("memo_id", UUID.class),
                    resultSet.getInt("memo_revision")))
        .optional()
        .orElseThrow(() -> DomainException.notFound("Analysis run"));
  }

  private DispatchSnapshot findDispatch(
      UUID ownerId, UUID runId, UUID proposalId, boolean forUpdate) {
    String lockingClause = forUpdate ? " for update of r, d" : "";
    return db.sql(
            """
            select r.id as run_id,
                   r.memo_id,
                   r.memo_revision,
                   r.status,
                   r.schema_version,
                   r.analyzer_version,
                   r.prompt_version,
                   r.local_model_version,
                   r.embedding_model_version,
                   r.routing_policy_version,
                   r.ambiguity_reasons::text as ambiguity_reasons,
                   r.cloud_transfer_mode,
                   r.cloud_gateway_version,
                   r.cloud_provider_id,
                   r.cloud_model_version,
                   r.cloud_consent_policy_version,
                   r.cloud_outcome,
                   r.cloud_execution_contract_version,
                   r.cloud_authorization_checked_at,
                   r.cloud_accepted_consent_granted_at,
                   r.cloud_provider_request_token,
                   d.reserved_proposal_id,
                   d.validated_local_proposal,
                   d.validated_local_proposal_hash,
                   d.retrieval_context,
                   d.retrieval_context_hash,
                   d.retrieval_context_version,
                   d.retrieval_context_candidate_count,
                   d.local_decision_evidence_version,
                   d.local_decision_evidence::text as local_decision_evidence,
                   d.fallback_policy_version,
                   d.fallback_reason_codes::text as fallback_reason_codes,
                   d.model_contribution_status,
                   d.model_changed_fields::text as model_changed_fields,
                   d.invocation_policy_version,
                   d.invocation_mode,
                   d.invocation_reason_code,
                   d.approved_correction_context,
                   d.approved_correction_context_hash,
                   d.approved_correction_context_version,
                   d.approved_correction_context_count,
                   d.executor_binding_id,
                   d.call_timeout_ms,
                   d.max_attempts,
                   d.deadline_at,
                   d.attempt_history_version,
                   d.state as dispatch_state,
                   d.fence_token,
                   d.lease_expires_at,
                   d.finalized_at
              from analysis_runs r
              join analysis_run_dispatches d
                on d.analysis_run_id = r.id
               and d.owner_id = r.owner_id
             where r.id = :runId
               and r.owner_id = :ownerId
               and d.reserved_proposal_id = :proposalId
            """
                + lockingClause)
        .param("runId", runId)
        .param("ownerId", ownerId)
        .param("proposalId", proposalId)
        .query(this::mapDispatch)
        .optional()
        .orElseThrow(() -> DomainException.notFound("Analysis run"));
  }

  private DispatchSnapshot mapDispatch(ResultSet resultSet, int rowNumber) throws SQLException {
    String dispatchState = resultSet.getString("dispatch_state");
    String localProposalJson = resultSet.getString("validated_local_proposal");
    ObjectNode localProposal = null;
    if (localProposalJson == null) {
      if (!"FINALIZED".equals(dispatchState)) {
        throw new IllegalStateException("The durable local proposal is missing.");
      }
    } else {
      if (!Hashing.sha256(localProposalJson)
          .equals(resultSet.getString("validated_local_proposal_hash"))) {
        throw new IllegalStateException("The durable local proposal failed its integrity check.");
      }
      JsonNode parsedProposal = parse(localProposalJson);
      if (!(parsedProposal instanceof ObjectNode parsedObject)) {
        throw new IllegalStateException("The durable local proposal is not a JSON object.");
      }
      localProposal = parsedObject;
    }
    DurableInvocationEvidence invocationEvidence = mapInvocationEvidence(resultSet);
    DurableLocalDecisionEvidence localDecisionEvidence =
        mapLocalDecisionEvidence(resultSet, dispatchState, localProposal, invocationEvidence);
    Optional<TagRetrievalContext> tagRetrievalContext =
        mapTagRetrievalContext(resultSet, dispatchState);
    DurableApprovedCorrectionContext approvedCorrectionContext =
        mapApprovedCorrectionContext(resultSet, dispatchState, invocationEvidence);
    String attemptHistoryVersion = resultSet.getString("attempt_history_version");
    if (!"none".equals(attemptHistoryVersion)
        && !CURRENT_ATTEMPT_HISTORY_VERSION.equals(attemptHistoryVersion)) {
      throw new IllegalStateException("The durable attempt history version is unsupported.");
    }
    CloudGatewayDescriptor descriptor =
        new CloudGatewayDescriptor(
            resultSet.getString("cloud_gateway_version"),
            resultSet.getString("cloud_provider_id"),
            resultSet.getString("cloud_model_version"),
            resultSet.getString("cloud_consent_policy_version"),
            CloudTransferMode.valueOf(resultSet.getString("cloud_transfer_mode")));
    String providerRequestToken = resultSet.getString("cloud_provider_request_token");
    CloudRunEvidence evidence =
        new CloudRunEvidence(
            descriptor.transferMode().name(),
            descriptor.gatewayVersion(),
            descriptor.providerId(),
            descriptor.modelVersion(),
            descriptor.consentPolicyVersion(),
            CloudAnalysisOutcome.valueOf(resultSet.getString("cloud_outcome")),
            resultSet.getString("cloud_execution_contract_version"),
            instantOrNull(resultSet, "cloud_authorization_checked_at"),
            instantOrNull(resultSet, "cloud_accepted_consent_granted_at"),
            providerRequestToken == null
                ? null
                : new CloudProviderRequestToken(providerRequestToken));
    return new DispatchSnapshot(
        resultSet.getObject("run_id", UUID.class),
        resultSet.getObject("reserved_proposal_id", UUID.class),
        resultSet.getObject("memo_id", UUID.class),
        resultSet.getInt("memo_revision"),
        resultSet.getString("status"),
        resultSet.getString("schema_version"),
        new AnalysisProvenance(
            resultSet.getString("analyzer_version"),
            resultSet.getString("prompt_version"),
            resultSet.getString("local_model_version"),
            resultSet.getString("embedding_model_version")),
        resultSet.getString("routing_policy_version"),
        parseAmbiguityReasons(resultSet.getString("ambiguity_reasons")),
        descriptor,
        evidence,
        localProposal,
        tagRetrievalContext,
        invocationEvidence,
        approvedCorrectionContext,
        localDecisionEvidence,
        resultSet.getString("executor_binding_id"),
        resultSet.getInt("call_timeout_ms"),
        resultSet.getInt("max_attempts"),
        resultSet.getTimestamp("deadline_at").toInstant(),
        attemptHistoryVersion,
        dispatchState,
        resultSet.getLong("fence_token"),
        instantOrNull(resultSet, "lease_expires_at"),
        instantOrNull(resultSet, "finalized_at"));
  }

  private DurableInvocationEvidence mapInvocationEvidence(ResultSet resultSet) throws SQLException {
    String version = resultSet.getString("invocation_policy_version");
    String mode = resultSet.getString("invocation_mode");
    String reason = resultSet.getString("invocation_reason_code");
    if ("legacy-v0".equals(version)) {
      if (!"LEGACY_UNKNOWN".equals(mode) || !"LEGACY_UNKNOWN".equals(reason)) {
        throw new IllegalStateException("The legacy invocation evidence is incoherent.");
      }
      return DurableInvocationEvidence.legacy();
    }
    if (!AnalysisInvocationPolicy.VERSION.equals(version)) {
      throw new IllegalStateException("The durable invocation policy version is unsupported.");
    }
    try {
      AnalysisInvocationMode parsedMode = AnalysisInvocationMode.valueOf(mode);
      AnalysisInvocationReason parsedReason = AnalysisInvocationReason.valueOf(reason);
      if ((parsedMode == AnalysisInvocationMode.UNCERTAINTY_ONLY
              && parsedReason != AnalysisInvocationReason.SEMANTIC_UNCERTAINTY)
          || (parsedMode == AnalysisInvocationMode.AI_PREFERRED
              && parsedReason != AnalysisInvocationReason.SEMANTIC_UNCERTAINTY
              && parsedReason != AnalysisInvocationReason.AI_PREFERRED_POLICY)) {
        throw new IllegalStateException("The durable invocation evidence is incoherent.");
      }
      return new DurableInvocationEvidence(version, parsedMode, parsedReason);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("The durable invocation evidence is invalid.", exception);
    }
  }

  private DurableLocalDecisionEvidence mapLocalDecisionEvidence(
      ResultSet resultSet,
      String dispatchState,
      ObjectNode localProposal,
      DurableInvocationEvidence invocationEvidence)
      throws SQLException {
    String version = resultSet.getString("local_decision_evidence_version");
    String policyVersion = resultSet.getString("fallback_policy_version");
    ModelContributionStatus contributionStatus =
        ModelContributionStatus.valueOf(resultSet.getString("model_contribution_status"));
    List<AnalysisProposalChangedField> changedFields =
        parseEnumNames(
            resultSet.getString("model_changed_fields"), AnalysisProposalChangedField.class);
    if ("none".equals(version)) {
      if (resultSet.getString("local_decision_evidence") != null
          || !"legacy-v0".equals(policyVersion)
          || !parseEnumNames(resultSet.getString("fallback_reason_codes"), FallbackReasonCode.class)
              .isEmpty()
          || contributionStatus != ModelContributionStatus.NOT_RECORDED
          || !changedFields.isEmpty()) {
        throw new IllegalStateException("The legacy local-decision evidence is incoherent.");
      }
      return DurableLocalDecisionEvidence.legacy();
    }
    if (!LocalDecisionEvidenceProjection.EVIDENCE_VERSION.equals(version)
        || !LocalDecisionEvidenceProjection.FALLBACK_POLICY_VERSION.equals(policyVersion)) {
      throw new IllegalStateException(
          "The durable local-decision evidence version is unsupported.");
    }
    JsonNode parsedEvidence = parse(resultSet.getString("local_decision_evidence"));
    if (!(parsedEvidence instanceof ObjectNode evidence)) {
      throw new IllegalStateException("The durable local-decision evidence is not a JSON object.");
    }
    List<FallbackReasonCode> reasons =
        parseEnumNames(resultSet.getString("fallback_reason_codes"), FallbackReasonCode.class);
    if (reasons.isEmpty() && !invocationEvidence.allowsEmptyFallbackReasons()) {
      throw new IllegalStateException("The durable fallback reasons are missing.");
    }
    if (localProposal != null) {
      LocalDecisionEvidenceProjection expected =
          localDecisionEvidenceProjector.project(localProposal);
      ObjectNode normalizedExpectedEvidence = (ObjectNode) parse(expected.evidence().toString());
      if (!normalizedExpectedEvidence.equals(evidence)) {
        throw new IllegalStateException(
            "The durable local-decision summary does not match at "
                + firstDifferentEvidenceField(normalizedExpectedEvidence, evidence)
                + ".");
      }
      if (!expected.fallbackReasonCodes().equals(reasons)) {
        throw new IllegalStateException("The durable fallback reasons do not match.");
      }
    }
    if (("PREPARED".equals(dispatchState) || "RUNNING".equals(dispatchState))
        && (contributionStatus != ModelContributionStatus.PENDING || !changedFields.isEmpty())) {
      throw new IllegalStateException("The pending model-contribution evidence is incoherent.");
    }
    return new DurableLocalDecisionEvidence(
        version, evidence, policyVersion, reasons, contributionStatus, changedFields);
  }

  private String firstDifferentEvidenceField(ObjectNode expected, ObjectNode actual) {
    for (String field :
        List.of(
            "version",
            "typeSummary",
            "temporalSummary",
            "taxonomySummary",
            "itemSummary",
            "relationCandidateCount")) {
      if (!expected.path(field).equals(actual.path(field))) {
        return field;
      }
    }
    return "document";
  }

  private Optional<TagRetrievalContext> mapTagRetrievalContext(
      ResultSet resultSet, String dispatchState) throws SQLException {
    String version = resultSet.getString("retrieval_context_version");
    int candidateCount = resultSet.getInt("retrieval_context_candidate_count");
    String rawContext = resultSet.getString("retrieval_context");
    String storedHash = resultSet.getString("retrieval_context_hash");
    if ("none".equals(version)) {
      if (candidateCount != 0 || rawContext != null || storedHash != null) {
        throw new IllegalStateException("The legacy retrieval context evidence is incoherent.");
      }
      return Optional.empty();
    }
    if (!TagRetrievalContext.CURRENT_VERSION.equals(version)) {
      throw new IllegalStateException("The durable retrieval context version is unsupported.");
    }
    if (rawContext == null) {
      if (!"FINALIZED".equals(dispatchState) || storedHash == null) {
        throw new IllegalStateException("The durable retrieval context is missing.");
      }
      return Optional.empty();
    }
    if (storedHash == null || !Hashing.sha256(rawContext).equals(storedHash)) {
      throw new IllegalStateException("The durable retrieval context failed its integrity check.");
    }
    TagRetrievalContext context = tagContextCodec.deserialize(rawContext);
    if (!version.equals(context.version()) || candidateCount != context.candidateCount()) {
      throw new IllegalStateException("The durable retrieval context evidence does not match.");
    }
    return Optional.of(context);
  }

  private DurableApprovedCorrectionContext mapApprovedCorrectionContext(
      ResultSet resultSet, String dispatchState, DurableInvocationEvidence invocationEvidence)
      throws SQLException {
    String version = resultSet.getString("approved_correction_context_version");
    int signalCount = resultSet.getInt("approved_correction_context_count");
    String rawContext = resultSet.getString("approved_correction_context");
    String storedHash = resultSet.getString("approved_correction_context_hash");
    if ("none".equals(version)) {
      return signalCount == 0 && rawContext == null && storedHash == null
          ? DurableApprovedCorrectionContext.disabled()
          : DurableApprovedCorrectionContext.invalid();
    }
    if (!ApprovedCorrectionContext.CURRENT_VERSION.equals(version)
        || !invocationEvidence.allowsApprovedCorrectionContext()
        || signalCount < 0
        || signalCount > ApprovedCorrectionContext.MAX_SIGNALS
        || storedHash == null
        || !storedHash.matches("[0-9a-f]{64}")) {
      return DurableApprovedCorrectionContext.invalid();
    }
    if (rawContext == null) {
      return "FINALIZED".equals(dispatchState)
          ? DurableApprovedCorrectionContext.scrubbed()
          : DurableApprovedCorrectionContext.invalid();
    }
    if (!"PREPARED".equals(dispatchState) && !"RUNNING".equals(dispatchState)) {
      return DurableApprovedCorrectionContext.invalid();
    }
    try {
      if (!Hashing.sha256(rawContext).equals(storedHash)) {
        return DurableApprovedCorrectionContext.invalid();
      }
      ApprovedCorrectionContext context = approvedCorrectionContextCodec.deserialize(rawContext);
      if (!version.equals(context.version()) || signalCount != context.signalCount()) {
        return DurableApprovedCorrectionContext.invalid();
      }
      return DurableApprovedCorrectionContext.current(context);
    } catch (RuntimeException exception) {
      return DurableApprovedCorrectionContext.invalid();
    }
  }

  private List<AmbiguityReason> parseAmbiguityReasons(String value) {
    JsonNode parsed = parse(value);
    if (!parsed.isArray()) {
      throw new IllegalStateException("Stored ambiguity reasons are not an array.");
    }
    java.util.ArrayList<AmbiguityReason> reasons = new java.util.ArrayList<>();
    for (JsonNode reason : parsed) {
      reasons.add(AmbiguityReason.valueOf(reason.asText()));
    }
    return List.copyOf(reasons);
  }

  private Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private void requireSameDispatchIdentity(DispatchIdentity observed, DispatchSnapshot locked) {
    if (!observed.runId().equals(locked.runId())
        || !observed.proposalId().equals(locked.proposalId())
        || !observed.memoId().equals(locked.memoId())
        || observed.memoRevision() != locked.memoRevision()) {
      throw DomainException.conflict(
          "ANALYSIS_RUN_CHANGED", "The durable analysis run changed during recovery.");
    }
  }

  private boolean matchesBinding(DispatchSnapshot dispatch, CloudGatewayBinding binding) {
    return binding != null
        && dispatch.executorBindingId().equals(binding.bindingId().value())
        && dispatch.descriptor().equals(binding.descriptor());
  }

  private CloudGatewayBinding bindGateway() {
    try {
      return cloudGateway.bind();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private void acquireOwnerApplicationLock(UUID ownerId) {
    String lockScope = ownerId + ":ANALYSIS_APPLICATION_OWNER";
    db.sql("select pg_advisory_xact_lock(hashtextextended(:lockScope, 0))")
        .param("lockScope", lockScope)
        .query(
            (resultSet, rowNumber) -> {
              resultSet.getObject(1);
              return rowNumber;
            })
        .single();
  }

  private void pauseBeforeCoordinationRetry() {
    try {
      Thread.sleep(COORDINATION_POLL_MILLIS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw DomainException.conflict(
          "ANALYSIS_IN_PROGRESS", "The analysis remains recoverable. Retry with the same key.");
    }
  }

  private <T> T inTransaction(Supplier<T> work) {
    T result = transactions.execute(status -> work.get());
    if (result == null) {
      throw new IllegalStateException("The analysis transaction returned no result.");
    }
    return result;
  }

  @Transactional(readOnly = true)
  public JsonNode proposal(UUID proposalId, String requestedSchemaVersion) {
    String responseSchemaVersion = responseSchemaVersion(requestedSchemaVersion);
    String proposalJson =
        db.sql(
                """
                select p.proposal_json::text
                  from analysis_proposals p
                  join analysis_runs r
                    on r.id = p.analysis_run_id
                   and r.owner_id = p.owner_id
                 where p.id = :proposalId
                   and p.owner_id = :ownerId
                """)
            .param("proposalId", proposalId)
            .param("ownerId", identity.ownerId())
            .query(String.class)
            .optional()
            .orElseThrow(() -> DomainException.notFound("Analysis proposal"));
    return projectProposal(parse(proposalJson), responseSchemaVersion);
  }

  @Transactional(readOnly = true)
  public List<ProposalRecoveryView> recoveryProposals(
      String status, int requestedLimit, String requestedSchemaVersion) {
    String responseSchemaVersion = responseSchemaVersion(requestedSchemaVersion);
    if (!SetLike.RECOVERABLE.contains(status)) {
      throw DomainException.invalid(
          "INVALID_PROPOSAL_STATUS",
          "status must be REVIEW_REQUIRED or POSTPONED for proposal recovery.");
    }
    int limit = Math.max(1, Math.min(requestedLimit, MAX_RECOVERY_PROPOSALS));
    return db.sql(
            """
            select p.id,
                   r.status,
                   p.created_at,
                   p.proposal_json::text as proposal_json
              from analysis_proposals p
              join analysis_runs r
                on r.id = p.analysis_run_id
               and r.owner_id = p.owner_id
              join memos m
                on m.id = r.memo_id
               and m.owner_id = r.owner_id
             where p.owner_id = :ownerId
               and r.status = :status
               and m.status = 'ACTIVE'
               and m.current_revision = r.memo_revision
             order by p.created_at desc, r.id desc, p.id desc
             limit :limit
            """)
        .param("ownerId", identity.ownerId())
        .param("status", status)
        .param("limit", limit)
        .query(
            (resultSet, rowNumber) ->
                new ProposalRecoveryView(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("status"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    projectProposal(
                        parse(resultSet.getString("proposal_json")), responseSchemaVersion)))
        .list();
  }

  private String responseSchemaVersion(String requestedSchemaVersion) {
    String version =
        requestedSchemaVersion == null ? LEGACY_PROPOSAL_SCHEMA_VERSION : requestedSchemaVersion;
    if (!LEGACY_PROPOSAL_SCHEMA_VERSION.equals(version)
        && !"2".equals(version)
        && !CURRENT_PROPOSAL_SCHEMA_VERSION.equals(version)) {
      throw DomainException.invalid(
          "UNSUPPORTED_PROPOSAL_SCHEMA_VERSION",
          "X-Analysis-Proposal-Schema-Version must be 1, 2, or 3.");
    }
    return version;
  }

  private JsonNode projectProposal(JsonNode proposal, String responseSchemaVersion) {
    if (!(proposal instanceof ObjectNode storedProposal)) {
      throw new IllegalStateException("Stored analysis proposal is not a JSON object.");
    }
    String storedSchemaVersion = storedProposal.path("schemaVersion").asText();
    if (!SetLike.SUPPORTED_SCHEMA_VERSIONS.contains(storedSchemaVersion)) {
      throw new IllegalStateException(
          "Stored analysis proposal has an unsupported schema version.");
    }
    if (schemaVersionRank(storedSchemaVersion) <= schemaVersionRank(responseSchemaVersion)) {
      return storedProposal;
    }

    ObjectNode projectedProposal = storedProposal.deepCopy();
    if (schemaVersionRank(responseSchemaVersion) < 3) {
      projectedProposal
          .path("itemCandidates")
          .forEach(
              candidate ->
                  ((ObjectNode) candidate)
                      .remove(
                          List.of("eventScheduleCandidates", "suggestedEventScheduleCandidateId")));
    }
    if (LEGACY_PROPOSAL_SCHEMA_VERSION.equals(responseSchemaVersion)) {
      projectedProposal
          .path("dateCandidates")
          .forEach(candidate -> ((ObjectNode) candidate).remove("candidateId"));
      projectedProposal
          .path("itemCandidates")
          .forEach(candidate -> ((ObjectNode) candidate).remove("dueDateCandidateId"));
    }
    projectedProposal.put("schemaVersion", responseSchemaVersion);
    proposalSchemaValidator.validate(projectedProposal);
    return projectedProposal;
  }

  private int schemaVersionRank(String schemaVersion) {
    return Integer.parseInt(schemaVersion);
  }

  @Transactional
  public ReviewDispositionView reject(UUID proposalId, String key) {
    return reviewDisposition(proposalId, key, REJECT_OPERATION, true);
  }

  @Transactional
  public ReviewDispositionView postpone(UUID proposalId, String key) {
    return reviewDisposition(proposalId, key, POSTPONE_OPERATION, false);
  }

  private ReviewDispositionView reviewDisposition(
      UUID proposalId, String key, String operation, boolean reject) {
    String requestHash = idempotency.hashRequest(new ProposalRequest(proposalId));
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(operation, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), ReviewDispositionView.class);
    }

    ProposalRun observedRun = findProposalRun(proposalId, false);
    MemoSnapshot memo = memos.getCurrentForUpdate(observedRun.memoId());
    ProposalRun run = findProposalRun(proposalId, true);
    requireSameProposalIdentity(observedRun, run);
    requireActiveCurrentRevision(memo, run.memoRevision());
    if ("STALE".equals(run.status())) {
      throw staleRevision();
    }

    String status;
    if (reject) {
      if (!SetLike.REJECTABLE.contains(run.status())) {
        throw DomainException.conflict(
            "PROPOSAL_NOT_REVIEWABLE", "The analysis proposal can no longer be rejected.");
      }
      status = "REJECTED";
      if (!"REJECTED".equals(run.status())) {
        db.sql(
                """
                update analysis_runs
                   set status = 'REJECTED'
                 where id = :runId
                   and owner_id = :ownerId
                """)
            .param("runId", run.runId())
            .param("ownerId", identity.ownerId())
            .update();
      }
    } else {
      if (!SetLike.POSTPONABLE.contains(run.status())) {
        throw DomainException.conflict(
            "PROPOSAL_NOT_REVIEWABLE", "Only a review-required proposal can be postponed.");
      }
      status = "POSTPONED";
      if (!"POSTPONED".equals(run.status())) {
        db.sql(
                """
                update analysis_runs
                   set status = 'POSTPONED'
                 where id = :runId
                   and owner_id = :ownerId
                """)
            .param("runId", run.runId())
            .param("ownerId", identity.ownerId())
            .update();
      }
    }

    ReviewDispositionView response = new ReviewDispositionView(proposalId, status);
    idempotency.store(operation, key, requestHash, proposalId, response);
    return response;
  }

  private ProposalRun findProposalRun(UUID proposalId, boolean forUpdate) {
    String lockingClause = forUpdate ? " for update of p, r" : "";
    return db.sql(
            """
            select r.id as run_id,
                   r.memo_id,
                   r.memo_revision,
                   r.status
              from analysis_proposals p
              join analysis_runs r
                on r.id = p.analysis_run_id
               and r.owner_id = p.owner_id
             where p.id = :proposalId
               and p.owner_id = :ownerId
            """
                + lockingClause)
        .param("proposalId", proposalId)
        .param("ownerId", identity.ownerId())
        .query(this::mapProposalRun)
        .optional()
        .orElseThrow(() -> DomainException.notFound("Analysis proposal"));
  }

  private void requireSameProposalIdentity(ProposalRun observed, ProposalRun locked) {
    if (!observed.runId().equals(locked.runId())
        || !observed.memoId().equals(locked.memoId())
        || observed.memoRevision() != locked.memoRevision()) {
      throw DomainException.conflict(
          "PROPOSAL_CHANGED", "The analysis proposal changed while it was being reviewed.");
    }
  }

  private ProposalRun mapProposalRun(ResultSet resultSet, int rowNumber) throws SQLException {
    return new ProposalRun(
        resultSet.getObject("run_id", UUID.class),
        resultSet.getObject("memo_id", UUID.class),
        resultSet.getInt("memo_revision"),
        resultSet.getString("status"));
  }

  private void validateProposalReferences(UUID ownerId, JsonNode proposal) {
    Set<UUID> referencedTagIds = new TreeSet<>();
    for (JsonNode tag : proposal.path("tagCandidates")) {
      if (tag.path("existingTagId").isTextual()) {
        referencedTagIds.add(UUID.fromString(tag.path("existingTagId").asText()));
      }
    }
    for (JsonNode relation : proposal.path("relationCandidates")) {
      UUID targetId = UUID.fromString(relation.path("targetId").asText());
      if ("TAG".equals(relation.path("targetType").asText())) {
        referencedTagIds.add(targetId);
      } else {
        boolean exists =
            db.sql(
                    """
                    select exists(
                      select 1
                        from memos
                       where id = :memoId
                         and owner_id = :ownerId
                         and status = 'ACTIVE'
                    )
                    """)
                .param("memoId", targetId)
                .param("ownerId", ownerId)
                .query(Boolean.class)
                .single();
        if (!exists) {
          throw DomainException.invalid(
              "INVALID_ANALYSIS_PROPOSAL", "A proposed relation references an unavailable memo.");
        }
      }
    }
    requireOwnedActiveTags(ownerId, referencedTagIds);
  }

  private TagRetrievalContext validatePreparedProposal(
      UUID ownerId,
      ObjectNode proposal,
      MemoSnapshot memo,
      String expectedSchemaVersion,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion) {
    validateProposalEnvelope(proposal, expectedSchemaVersion);
    TagRetrievalContext tagRetrievalContext = tagContextRetriever.resolve(ownerId, proposal);
    validateResolvedProposal(
        ownerId, proposal, memo, expectedProvenance, expectedRoutingPolicyVersion);
    return tagRetrievalContext;
  }

  private void validateProposal(
      UUID ownerId,
      ObjectNode proposal,
      MemoSnapshot memo,
      String expectedSchemaVersion,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion) {
    validateProposalEnvelope(proposal, expectedSchemaVersion);
    validateResolvedProposal(
        ownerId, proposal, memo, expectedProvenance, expectedRoutingPolicyVersion);
  }

  private void validateProposalEnvelope(ObjectNode proposal, String expectedSchemaVersion) {
    if (proposal == null) {
      throw DomainException.invalid(
          "INVALID_ANALYSIS_PROPOSAL", "The analyzer returned no proposal.");
    }
    if (!expectedSchemaVersion.equals(proposal.path("schemaVersion").asText())) {
      throw DomainException.invalid(
          "INVALID_ANALYSIS_PROPOSAL",
          "The proposal schema version does not match the server-owned analyzer contract.");
    }
    proposalSchemaValidator.validate(proposal);
  }

  private void validateResolvedProposal(
      UUID ownerId,
      ObjectNode proposal,
      MemoSnapshot memo,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion) {
    synchronizeNewTopicSignal(proposal);
    synchronizeResolvedRouteMetadata(proposal);
    proposalSchemaValidator.validate(proposal);
    proposalValidator.validate(
        proposal,
        memo.id(),
        memo.currentRevision(),
        memo.content(),
        expectedProvenance,
        expectedRoutingPolicyVersion);
    validateProposalReferences(ownerId, proposal);
  }

  private void synchronizeNewTopicSignal(ObjectNode proposal) {
    boolean hasNewTag = false;
    for (JsonNode candidate : proposal.path("tagCandidates")) {
      if (candidate.path("isNewProposal").isBoolean()
          && candidate.path("isNewProposal").asBoolean()
          && candidate.path("existingTagId").isNull()) {
        hasNewTag = true;
        break;
      }
    }

    ArrayNode reasons = (ArrayNode) proposal.path("ambiguityReasons");
    for (int index = reasons.size() - 1; index >= 0; index--) {
      if (AmbiguityReason.NEW_TOPIC.name().equals(reasons.get(index).asText())) {
        reasons.remove(index);
      }
    }
    if (hasNewTag) {
      reasons.add(AmbiguityReason.NEW_TOPIC.name());
    }
  }

  private void synchronizeResolvedRouteMetadata(ObjectNode proposal) {
    JsonNode metadata = proposal.path("providerMetadata");
    if (metadata instanceof ObjectNode objectMetadata) {
      objectMetadata.put(
          "route", ambiguityGate.route(ambiguityGate.routingSignals(proposal)).name());
    }
  }

  private AnalysisProvenance requireAnalysisProvenance() {
    AnalysisProvenance provenance = analyzer.provenance();
    if (provenance == null) {
      throw new IllegalStateException("Local analyzer must expose analysis provenance.");
    }
    return provenance;
  }

  private String requireProposalSchemaVersion() {
    String version = analyzer.proposalSchemaVersion();
    if (!SetLike.SUPPORTED_SCHEMA_VERSIONS.contains(version)) {
      throw new IllegalStateException(
          "Local analyzer must expose a supported proposal schema version.");
    }
    return version;
  }

  private String requireRoutingPolicyVersion() {
    String version = ambiguityGate.version();
    if (version == null
        || version.isBlank()
        || version.codePointCount(0, version.length()) > AnalysisProvenance.MAX_VERSION_LENGTH) {
      throw new IllegalStateException(
          "The ambiguity gate must expose a version of 1 to 64 characters.");
    }
    return version;
  }

  private CloudEnrichment validatedFallback(
      UUID ownerId,
      ObjectNode localProposal,
      List<AmbiguityReason> routingReasons,
      String routingPolicyVersion,
      MemoSnapshot memo,
      String expectedSchemaVersion,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion,
      CloudRunEvidence evidence) {
    ObjectNode fallback = localProposal.deepCopy();
    canonicalizeProviderMetadata(fallback, fallback);
    normalizeDefaultFallbackForReview(fallback);
    stampCloudMetadata(fallback, routingReasons, routingPolicyVersion, evidence);
    validateProposal(
        ownerId,
        fallback,
        memo,
        expectedSchemaVersion,
        expectedProvenance,
        expectedRoutingPolicyVersion);
    return new CloudEnrichment(fallback, evidence);
  }

  private void normalizeDefaultFallbackForReview(ObjectNode fallback) {
    if (!"DEFAULT_FALLBACK"
        .equals(fallback.path("providerMetadata").path("classificationBasis").asText())) {
      return;
    }
    ArrayNode unknownTypes = json.createArrayNode();
    unknownTypes.add(json.createObjectNode().put("value", "UNKNOWN").put("score", 0.52));
    fallback.set("typeCandidates", unknownTypes);
    fallback.set("itemCandidates", json.createArrayNode());
    fallback.set("relationCandidates", json.createArrayNode());
    JsonNode reasonsNode = fallback.path("ambiguityReasons");
    if (reasonsNode instanceof ArrayNode reasons && !containsText(reasons, "MISSING_ACTION")) {
      reasons.add("MISSING_ACTION");
    }
  }

  private boolean containsText(ArrayNode values, String expected) {
    for (JsonNode value : values) {
      if (expected.equals(value.asText())) {
        return true;
      }
    }
    return false;
  }

  private CloudAnalysisOutcome outcomeFor(CloudAnalysisFailureReason reason) {
    return switch (reason) {
      case UNAVAILABLE -> CloudAnalysisOutcome.UNAVAILABLE;
      case TIMEOUT -> CloudAnalysisOutcome.TIMEOUT;
      case RETRY_EXHAUSTED -> CloudAnalysisOutcome.RETRY_EXHAUSTED;
      case PROVIDER_ERROR -> CloudAnalysisOutcome.PROVIDER_ERROR;
      case UNEXPECTED_FAILURE -> CloudAnalysisOutcome.UNEXPECTED_FAILURE;
    };
  }

  private void canonicalizeProviderMetadata(ObjectNode proposal, ObjectNode trustedSource) {
    JsonNode source = trustedSource.path("providerMetadata");
    if (!(source instanceof ObjectNode)) {
      return;
    }
    ObjectNode bounded = json.createObjectNode();
    for (String field : REQUIRED_PROVIDER_METADATA) {
      bounded.set(field, source.path(field).deepCopy());
    }
    for (String field : BOUNDED_LOCAL_PROVIDER_METADATA) {
      JsonNode value = source.path(field);
      if (isBoundedMetadataValue(value)) {
        bounded.set(field, value.deepCopy());
      }
    }
    proposal.set("providerMetadata", bounded);
  }

  private boolean isBoundedMetadataValue(JsonNode value) {
    if (value.isIntegralNumber()) {
      return value.canConvertToInt();
    }
    return value.isTextual()
        && !value.asText().isBlank()
        && value.asText().codePointCount(0, value.asText().length())
            <= AnalysisProvenance.MAX_VERSION_LENGTH;
  }

  private Optional<Instant> acceptedPinnedCloudConsent(
      UUID ownerId, String consentPolicyVersion, Instant authorizationInstant) {
    return db.sql(
            """
            select cloud_analysis_consent,
                   cloud_analysis_consent_policy_version,
                   cloud_analysis_consent_granted_at
              from user_settings
             where user_id = :ownerId
             for share
            """)
        .param("ownerId", ownerId)
        .query(
            (resultSet, rowNumber) ->
                new CloudConsentState(
                    resultSet.getBoolean("cloud_analysis_consent"),
                    resultSet.getString("cloud_analysis_consent_policy_version"),
                    resultSet.getTimestamp("cloud_analysis_consent_granted_at")))
        .optional()
        .filter(
            consent ->
                consent.granted()
                    && consentPolicyVersion.equals(consent.policyVersion())
                    && wasGrantedBy(consent.grantedAt(), authorizationInstant))
        .map(consent -> consent.grantedAt().toInstant());
  }

  private boolean wasGrantedBy(Timestamp grantedAt, Instant authorizationInstant) {
    return grantedAt != null && !grantedAt.toInstant().isAfter(authorizationInstant);
  }

  private void stampCloudMetadata(
      ObjectNode proposal,
      List<AmbiguityReason> routingReasons,
      String routingPolicyVersion,
      CloudRunEvidence evidence) {
    JsonNode metadataNode = proposal.path("providerMetadata");
    if (!(metadataNode instanceof ObjectNode metadata)) {
      return;
    }
    RESERVED_CLOUD_METADATA.forEach(metadata::remove);
    metadata
        .put("toolCalls", 0)
        .put("cloudTransferMode", evidence.transferMode())
        .put("cloudGatewayVersion", evidence.gatewayVersion())
        .put("cloudProviderId", evidence.providerId())
        .put("cloudModelVersion", evidence.modelVersion())
        .put("cloudConsentPolicyVersion", evidence.consentPolicyVersion())
        .put("cloudOutcome", evidence.outcome().name())
        .put("cloudToolCalls", 0)
        .put("cloudMutationCalls", 0)
        .putArray("cloudResolvedFields");
    metadata.put("receivedRoutingPolicyVersion", routingPolicyVersion);
    var receivedReasons = metadata.putArray("receivedRoutingReasons");
    routingReasons.forEach(reason -> receivedReasons.add(reason.name()));
  }

  private Timestamp timestampOrNull(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private String serializeAmbiguityReasons(List<AmbiguityReason> reasons) {
    var values = json.createArrayNode();
    for (AmbiguityReason reason : reasons) {
      values.add(reason.name());
    }
    return values.toString();
  }

  private String serializeEnumNames(List<? extends Enum<?>> values) {
    ArrayNode names = json.createArrayNode();
    values.forEach(value -> names.add(value.name()));
    return names.toString();
  }

  private <E extends Enum<E>> List<E> parseEnumNames(String value, Class<E> enumType) {
    JsonNode parsed = parse(value);
    if (!parsed.isArray()) {
      throw new IllegalStateException("Stored enum evidence is not an array.");
    }
    java.util.ArrayList<E> values = new java.util.ArrayList<>();
    for (JsonNode element : parsed) {
      if (!element.isTextual()) {
        throw new IllegalStateException("Stored enum evidence contains a non-text value.");
      }
      values.add(Enum.valueOf(enumType, element.asText()));
    }
    return List.copyOf(values);
  }

  private void requireOwnedActiveTags(UUID ownerId, Set<UUID> tagIds) {
    if (tagIds.isEmpty()) {
      return;
    }
    List<UUID> lockedTagIds =
        db.sql(
                """
                select id
                  from tags
                 where owner_id = :ownerId
                   and state = 'ACTIVE'
                   and id in (:tagIds)
                 order by id
                 for key share
                """)
            .param("ownerId", ownerId)
            .param("tagIds", List.copyOf(tagIds))
            .query(UUID.class)
            .list();
    if (lockedTagIds.size() != tagIds.size() || !tagIds.containsAll(lockedTagIds)) {
      throw DomainException.invalid(
          "INVALID_ANALYSIS_PROPOSAL", "A proposed tag is not available to this owner.");
    }
  }

  private void requireActiveCurrentRevision(MemoSnapshot memo, int expectedRevision) {
    if (!memo.isActive()) {
      throw DomainException.conflict("MEMO_NOT_ACTIVE", "The memo is not active.");
    }
    if (memo.currentRevision() != expectedRevision) {
      throw staleRevision();
    }
  }

  private DomainException staleRevision() {
    return DomainException.conflict(
        "STALE_MEMO_REVISION", "The memo changed after this analysis was requested.");
  }

  private JsonNode parse(String value) {
    try {
      return json.readTree(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not parse a validated analysis proposal.", exception);
    }
  }

  private record StartRequest(UUID memoId, Start request) {}

  private record ProposalRequest(UUID proposalId) {}

  private record ProposalRun(UUID runId, UUID memoId, int memoRevision, String status) {}

  private record CloudConsentState(boolean granted, String policyVersion, Timestamp grantedAt) {}

  private record CloudEnrichment(ObjectNode proposal, CloudRunEvidence evidence) {}

  private record ModelContribution(
      ModelContributionStatus status, List<AnalysisProposalChangedField> changedFields) {
    private ModelContribution {
      changedFields = List.copyOf(changedFields);
    }
  }

  private interface StartDecision {}

  private record StartCompleted(RunView response) implements StartDecision {}

  private record StartStale(RunView response) implements StartDecision {}

  private record StartNeedsBinding(UUID runId, UUID proposalId, CloudGatewayBinding binding)
      implements StartDecision {}

  private record StartWaiting(UUID runId, UUID proposalId, CloudGatewayBinding binding)
      implements StartDecision {}

  private record StartDispatch(
      UUID runId,
      UUID proposalId,
      long fenceToken,
      String attemptHistoryVersion,
      CloudGatewayBinding binding,
      CloudAnalysisRequest request,
      Duration callTimeout)
      implements StartDecision {}

  private record DispatchIdentity(UUID runId, UUID proposalId, UUID memoId, int memoRevision) {}

  private record RecoveryCandidate(
      UUID ownerId,
      UUID runId,
      UUID proposalId,
      String idempotencyKeyHash,
      String requestHash,
      String key) {
    @Override
    public String toString() {
      return "RecoveryCandidate[redacted]";
    }
  }

  private record DispatchSnapshot(
      UUID runId,
      UUID proposalId,
      UUID memoId,
      int memoRevision,
      String status,
      String schemaVersion,
      AnalysisProvenance provenance,
      String routingPolicyVersion,
      List<AmbiguityReason> routingReasons,
      CloudGatewayDescriptor descriptor,
      CloudRunEvidence evidence,
      ObjectNode localProposal,
      Optional<TagRetrievalContext> tagRetrievalContext,
      DurableInvocationEvidence invocationEvidence,
      DurableApprovedCorrectionContext approvedCorrectionContext,
      DurableLocalDecisionEvidence localDecisionEvidence,
      String executorBindingId,
      int callTimeoutMs,
      int maxAttempts,
      Instant deadlineAt,
      String attemptHistoryVersion,
      String dispatchState,
      long fenceToken,
      Instant leaseExpiresAt,
      Instant finalizedAt) {

    private Instant acceptedConsentGrantedAt() {
      return evidence.acceptedConsentGrantedAt();
    }
  }

  private record DurableInvocationEvidence(
      String policyVersion, AnalysisInvocationMode mode, AnalysisInvocationReason reason) {

    private static DurableInvocationEvidence legacy() {
      return new DurableInvocationEvidence("legacy-v0", null, null);
    }

    private boolean allowsEmptyFallbackReasons() {
      return AnalysisInvocationPolicy.VERSION.equals(policyVersion)
          && mode == AnalysisInvocationMode.AI_PREFERRED
          && reason == AnalysisInvocationReason.AI_PREFERRED_POLICY;
    }

    private boolean allowsApprovedCorrectionContext() {
      return AnalysisInvocationPolicy.VERSION.equals(policyVersion)
          && mode == AnalysisInvocationMode.AI_PREFERRED;
    }

    private boolean compatibleWith(CloudGatewayDescriptor descriptor) {
      return !AnalysisInvocationPolicy.VERSION.equals(policyVersion)
          || mode != AnalysisInvocationMode.AI_PREFERRED
          || descriptor.transferMode() == CloudTransferMode.LOCAL_MACHINE_MEMO_CONTENT;
    }
  }

  private record DurableApprovedCorrectionContext(
      boolean valid, Optional<ApprovedCorrectionContext> context) {

    private DurableApprovedCorrectionContext {
      context = java.util.Objects.requireNonNull(context, "context");
    }

    private static DurableApprovedCorrectionContext disabled() {
      return new DurableApprovedCorrectionContext(true, Optional.empty());
    }

    private static DurableApprovedCorrectionContext scrubbed() {
      return new DurableApprovedCorrectionContext(true, Optional.empty());
    }

    private static DurableApprovedCorrectionContext current(ApprovedCorrectionContext context) {
      return new DurableApprovedCorrectionContext(true, Optional.of(context));
    }

    private static DurableApprovedCorrectionContext invalid() {
      return new DurableApprovedCorrectionContext(false, Optional.empty());
    }
  }

  private record DurableLocalDecisionEvidence(
      String evidenceVersion,
      ObjectNode evidence,
      String fallbackPolicyVersion,
      List<FallbackReasonCode> fallbackReasonCodes,
      ModelContributionStatus contributionStatus,
      List<AnalysisProposalChangedField> changedFields) {

    private DurableLocalDecisionEvidence {
      evidence = evidence == null ? null : evidence.deepCopy();
      fallbackReasonCodes = List.copyOf(fallbackReasonCodes);
      changedFields = List.copyOf(changedFields);
    }

    private static DurableLocalDecisionEvidence legacy() {
      return new DurableLocalDecisionEvidence(
          "none", null, "legacy-v0", List.of(), ModelContributionStatus.NOT_RECORDED, List.of());
    }

    private boolean current() {
      return LocalDecisionEvidenceProjection.EVIDENCE_VERSION.equals(evidenceVersion);
    }
  }

  private record CloudRunEvidence(
      String transferMode,
      String gatewayVersion,
      String providerId,
      String modelVersion,
      String consentPolicyVersion,
      CloudAnalysisOutcome outcome,
      String executionContractVersion,
      Instant authorizationCheckedAt,
      Instant acceptedConsentGrantedAt,
      CloudProviderRequestToken providerRequestToken) {

    private static CloudRunEvidence notRequired() {
      return new CloudRunEvidence(
          "NOT_REQUIRED",
          "none",
          "none",
          "none",
          "none",
          CloudAnalysisOutcome.NOT_REQUIRED,
          "snapshot-v1",
          null,
          null,
          null);
    }

    private static CloudRunEvidence descriptorUnavailable(CloudAnalysisOutcome outcome) {
      return new CloudRunEvidence(
          "DESCRIPTOR_UNAVAILABLE",
          "unavailable",
          "unavailable",
          "unavailable",
          "unavailable",
          outcome,
          "snapshot-v1",
          null,
          null,
          null);
    }

    private static CloudRunEvidence consentRequired(
        CloudGatewayDescriptor descriptor, Instant authorizationCheckedAt) {
      return new CloudRunEvidence(
          descriptor.transferMode().name(),
          descriptor.gatewayVersion(),
          descriptor.providerId(),
          descriptor.modelVersion(),
          descriptor.consentPolicyVersion(),
          CloudAnalysisOutcome.CONSENT_REQUIRED,
          "snapshot-v1",
          authorizationCheckedAt,
          null,
          null);
    }

    private static CloudRunEvidence durableConsentRequired(
        CloudGatewayDescriptor descriptor, Instant authorizationCheckedAt) {
      return new CloudRunEvidence(
          descriptor.transferMode().name(),
          descriptor.gatewayVersion(),
          descriptor.providerId(),
          descriptor.modelVersion(),
          descriptor.consentPolicyVersion(),
          CloudAnalysisOutcome.CONSENT_REQUIRED,
          DURABLE_EXECUTION_CONTRACT_VERSION,
          authorizationCheckedAt,
          null,
          null);
    }

    private static CloudRunEvidence pending(
        CloudGatewayDescriptor descriptor,
        Optional<Instant> authorizationCheckedAt,
        Optional<Instant> acceptedConsentGrantedAt,
        CloudProviderRequestToken providerRequestToken) {
      return new CloudRunEvidence(
          descriptor.transferMode().name(),
          descriptor.gatewayVersion(),
          descriptor.providerId(),
          descriptor.modelVersion(),
          descriptor.consentPolicyVersion(),
          CloudAnalysisOutcome.PENDING,
          DURABLE_EXECUTION_CONTRACT_VERSION,
          authorizationCheckedAt.orElse(null),
          acceptedConsentGrantedAt.orElse(null),
          providerRequestToken);
    }

    private static CloudRunEvidence from(
        CloudAnalysisRequest request, CloudAnalysisOutcome outcome) {
      CloudGatewayDescriptor descriptor = request.descriptor();
      return new CloudRunEvidence(
          descriptor.transferMode().name(),
          descriptor.gatewayVersion(),
          descriptor.providerId(),
          descriptor.modelVersion(),
          descriptor.consentPolicyVersion(),
          outcome,
          DURABLE_EXECUTION_CONTRACT_VERSION,
          request.authorizationCheckedAt().orElse(null),
          request.acceptedConsentGrantedAt().orElse(null),
          request.providerRequestToken());
    }

    private CloudRunEvidence withOutcome(CloudAnalysisOutcome nextOutcome) {
      return new CloudRunEvidence(
          transferMode,
          gatewayVersion,
          providerId,
          modelVersion,
          consentPolicyVersion,
          nextOutcome,
          executionContractVersion,
          authorizationCheckedAt,
          acceptedConsentGrantedAt,
          providerRequestToken);
    }

    private CloudRunEvidence withAuthorization(Instant checkedAt, Instant grantedAt) {
      return new CloudRunEvidence(
          transferMode,
          gatewayVersion,
          providerId,
          modelVersion,
          consentPolicyVersion,
          outcome,
          executionContractVersion,
          checkedAt,
          grantedAt,
          providerRequestToken);
    }
  }

  private static final class SetLike {
    private static final List<String> SUPPORTED_SCHEMA_VERSIONS = List.of("1", "2", "3");
    private static final java.util.Set<String> RECOVERABLE =
        java.util.Set.of("REVIEW_REQUIRED", "POSTPONED");
    private static final java.util.Set<String> REJECTABLE =
        java.util.Set.of("REVIEW_REQUIRED", "POSTPONED", "REJECTED");
    private static final java.util.Set<String> POSTPONABLE =
        java.util.Set.of("REVIEW_REQUIRED", "POSTPONED");

    private SetLike() {}
  }
}
