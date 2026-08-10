package local.personalmemo.analysis.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.domain.AnalysisRoute;
import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisOutcome;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudGatewayBindingId;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudProviderRequestToken;
import local.personalmemo.analysis.domain.CloudTransferMode;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.domain.LocalAnalyzer;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.idempotency.IdempotencyService;
import local.personalmemo.common.security.Hashing;
import local.personalmemo.memo.application.MemoService;
import local.personalmemo.memo.domain.MemoSnapshot;
import local.personalmemo.taxonomy.domain.TagNormalizer;
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
  private static final String CURRENT_PROPOSAL_SCHEMA_VERSION = "2";
  private static final String DURABLE_EXECUTION_CONTRACT_VERSION = "durable-v1";
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
          "emittedItemCandidateCount");

  private final JdbcClient db;
  private final CurrentIdentity identity;
  private final MemoService memos;
  private final LocalAnalyzer analyzer;
  private final CloudAnalysisGateway cloudGateway;
  private final BoundedCloudGatewayInvoker cloudInvoker;
  private final Duration cloudAttemptTimeout;
  private final DeterministicAmbiguityGate ambiguityGate;
  private final AnalysisProposalSchemaValidator proposalSchemaValidator;
  private final AnalysisProposalValidator proposalValidator;
  private final IdempotencyService idempotency;
  private final TagNormalizer tagNormalizer;
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
      AnalysisProposalSchemaValidator proposalSchemaValidator,
      AnalysisProposalValidator proposalValidator,
      IdempotencyService idempotency,
      TagNormalizer tagNormalizer,
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
    this.proposalSchemaValidator = proposalSchemaValidator;
    this.proposalValidator = proposalValidator;
    this.idempotency = idempotency;
    this.tagNormalizer = tagNormalizer;
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
        CloudAnalysisResult result = invokeForCaller(dispatch);
        CloudAnalysisResult completedResult = result;
        decision =
            inTransaction(
                () -> finalizeStart(ownerId, dispatch, completedResult, key, requestHash));
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
        CloudAnalysisResult result = invokeForRecovery(dispatch);
        if (result == null) {
          return false;
        }
        CloudAnalysisResult completedResult = result;
        decision =
            inTransaction(
                () ->
                    finalizeStart(
                        candidate.ownerId(),
                        dispatch,
                        completedResult,
                        candidate.key(),
                        candidate.requestHash()));
        continue;
      }
      throw new IllegalStateException("Unknown durable recovery decision.");
    }
    return false;
  }

  private CloudAnalysisResult invokeForCaller(StartDispatch dispatch) {
    try {
      return cloudInvoker.invoke(dispatch.binding(), dispatch.request(), dispatch.callTimeout());
    } catch (CloudGatewayInvocationException exception) {
      if (exception.reason() == CloudGatewayInvocationException.Reason.CALLER_INTERRUPTED) {
        throw DomainException.conflict(
            "ANALYSIS_IN_PROGRESS", "The analysis remains recoverable. Retry with the same key.");
      }
      return CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE);
    } catch (IllegalArgumentException exception) {
      return CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE);
    }
  }

  private CloudAnalysisResult invokeForRecovery(StartDispatch dispatch) {
    try {
      return cloudInvoker.invoke(dispatch.binding(), dispatch.request(), dispatch.callTimeout());
    } catch (CloudGatewayInvocationException exception) {
      if (exception.reason() == CloudGatewayInvocationException.Reason.CALLER_INTERRUPTED) {
        return null;
      }
      return CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE);
    } catch (IllegalArgumentException exception) {
      return CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE);
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
    validateProposal(
        ownerId, localProposal, memo, proposalSchemaVersion, provenance, routingPolicyVersion);
    List<AmbiguityReason> routingReasons = ambiguityGate.routingSignals(localProposal);
    AnalysisRoute route = ambiguityGate.route(routingReasons);

    if (route == AnalysisRoute.LOCAL_REVIEW) {
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

    CloudAnalysisRequest cloudRequest =
        new CloudAnalysisRequest(
            localProposal,
            routingReasons,
            routingPolicyVersion,
            descriptor,
            authorizationCheckedAt,
            acceptedConsentGrantedAt,
            CloudProviderRequestToken.issue(ownerId, START_OPERATION, key, requestHash));
    CloudRunEvidence pendingEvidence = CloudRunEvidence.pending(cloudRequest);
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
      Instant preparedAt) {
    String proposalJson = localProposal.toString();
    long timeoutMillis = cloudAttemptTimeout.toMillis();
    db.sql(
            """
            insert into analysis_run_dispatches(
              analysis_run_id, owner_id, reserved_proposal_id, idempotency_key_hash,
              request_hash, validated_local_proposal, validated_local_proposal_hash,
              executor_binding_id, call_timeout_ms, max_attempts, deadline_at, state,
              fence_token, prepared_at, updated_at
            ) values (
              :runId, :ownerId, :proposalId, :idempotencyKeyHash,
              :requestHash, :localProposal, :localProposalHash,
              :bindingId, :callTimeoutMs, :maxAttempts, :deadlineAt, 'PREPARED',
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
        .param("bindingId", bindingId.value())
        .param("callTimeoutMs", timeoutMillis)
        .param("maxAttempts", MAX_GATEWAY_ATTEMPTS)
        .param("deadlineAt", Timestamp.from(preparedAt.plus(DISPATCH_WINDOW)))
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
    if ("STALE".equals(dispatch.status())
        || !memo.isActive()
        || memo.currentRevision() != dispatch.memoRevision()) {
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
          Instant.now());
    }

    Instant now = Instant.now();
    if ("RUNNING".equals(dispatch.dispatchState())
        && dispatch.leaseExpiresAt() != null
        && dispatch.leaseExpiresAt().isAfter(now)) {
      return new StartWaiting(runId, proposalId, binding);
    }
    Duration remainingDispatchWindow = Duration.between(now, dispatch.deadlineAt());
    if (remainingDispatchWindow.compareTo(Duration.ofMillis(1)) < 0
        || dispatch.fenceToken() >= dispatch.maxAttempts()) {
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
    updateRunForAttempt(ownerId, runId, attemptEvidence);

    CloudAnalysisRequest request =
        new CloudAnalysisRequest(
            dispatch.localProposal(),
            dispatch.routingReasons(),
            dispatch.routingPolicyVersion(),
            dispatch.descriptor(),
            Optional.ofNullable(attemptEvidence.authorizationCheckedAt()),
            Optional.ofNullable(attemptEvidence.acceptedConsentGrantedAt()),
            attemptEvidence.providerRequestToken());
    return new StartDispatch(runId, proposalId, nextFence, binding, request, attemptTimeout);
  }

  private StartDecision finalizeStart(
      UUID ownerId,
      StartDispatch attempt,
      CloudAnalysisResult result,
      String key,
      String requestHash) {
    RunView replay = requireStartReplay(ownerId, key, requestHash);
    if (!"RUNNING".equals(replay.status())) {
      return decisionFromFinalResponse(replay);
    }

    DispatchIdentity observed =
        findDispatchIdentity(ownerId, attempt.runId(), attempt.proposalId());
    MemoSnapshot memo = memos.getCurrentForUpdate(ownerId, observed.memoId());
    DispatchSnapshot dispatch = findDispatch(ownerId, attempt.runId(), attempt.proposalId(), true);
    requireSameDispatchIdentity(observed, dispatch);
    if (dispatch.finalizedAt() != null || "FINALIZED".equals(dispatch.dispatchState())) {
      return decisionFromFinalResponse(requireStartReplay(ownerId, key, requestHash));
    }
    if (dispatch.fenceToken() != attempt.fenceToken()) {
      return new StartWaiting(attempt.runId(), attempt.proposalId(), attempt.binding());
    }

    Instant completedAt = Instant.now();
    CloudRunEvidence resultEvidence = evidenceForResult(attempt.request(), result);
    if ("STALE".equals(dispatch.status())
        || !memo.isActive()
        || memo.currentRevision() != dispatch.memoRevision()) {
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
        .param("cloudAuthorizationCheckedAt", timestampOrNull(evidence.authorizationCheckedAt()))
        .param(
            "cloudAcceptedConsentGrantedAt", timestampOrNull(evidence.acceptedConsentGrantedAt()))
        .param(
            "cloudProviderRequestToken",
            evidence.providerRequestToken() == null
                ? null
                : evidence.providerRequestToken().value())
        .param("completedAt", Timestamp.from(completedAt))
        .param("runId", dispatch.runId())
        .param("ownerId", ownerId)
        .update();
    insertProposal(ownerId, dispatch.proposalId(), dispatch.runId(), proposal, completedAt);
    db.sql(
            """
             update analysis_run_dispatches
                set state = 'FINALIZED',
                    validated_local_proposal = null,
                    lease_expires_at = null,
                    finalized_at = :finalizedAt,
                    updated_at = :finalizedAt
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
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
                   d.executor_binding_id,
                   d.call_timeout_ms,
                   d.max_attempts,
                   d.deadline_at,
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
        resultSet.getString("executor_binding_id"),
        resultSet.getInt("call_timeout_ms"),
        resultSet.getInt("max_attempts"),
        resultSet.getTimestamp("deadline_at").toInstant(),
        dispatchState,
        resultSet.getLong("fence_token"),
        instantOrNull(resultSet, "lease_expires_at"),
        instantOrNull(resultSet, "finalized_at"));
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
        && !CURRENT_PROPOSAL_SCHEMA_VERSION.equals(version)) {
      throw DomainException.invalid(
          "UNSUPPORTED_PROPOSAL_SCHEMA_VERSION",
          "X-Analysis-Proposal-Schema-Version must be 1 or 2.");
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
    if (CURRENT_PROPOSAL_SCHEMA_VERSION.equals(responseSchemaVersion)
        || LEGACY_PROPOSAL_SCHEMA_VERSION.equals(storedSchemaVersion)) {
      return storedProposal;
    }

    ObjectNode legacyProposal = storedProposal.deepCopy();
    legacyProposal.put("schemaVersion", LEGACY_PROPOSAL_SCHEMA_VERSION);
    legacyProposal
        .path("dateCandidates")
        .forEach(candidate -> ((ObjectNode) candidate).remove("candidateId"));
    legacyProposal
        .path("itemCandidates")
        .forEach(candidate -> ((ObjectNode) candidate).remove("dueDateCandidateId"));
    proposalSchemaValidator.validate(legacyProposal);
    return legacyProposal;
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

  private void validateProposal(
      UUID ownerId,
      ObjectNode proposal,
      MemoSnapshot memo,
      String expectedSchemaVersion,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion) {
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
    resolveOwnerScopedTagCandidates(ownerId, proposal);
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

  /**
   * Converts only structurally valid, explicitly new tag candidates into canonical references. The
   * analyzer has no owner context, so it must never invent persistence identifiers. A candidate is
   * resolved only when its normalized canonical/alias values identify exactly one active tag owned
   * by the authenticated user.
   */
  private void resolveOwnerScopedTagCandidates(UUID ownerId, ObjectNode proposal) {
    for (JsonNode candidateNode : proposal.path("tagCandidates")) {
      if (!candidateNode.path("isNewProposal").isBoolean()
          || !candidateNode.path("isNewProposal").asBoolean()
          || !candidateNode.path("existingTagId").isNull()) {
        continue;
      }

      Optional<String> normalizedCanonical =
          normalizeForResolution(candidateNode.path("canonicalName"));
      if (normalizedCanonical.isEmpty()) {
        continue;
      }
      Optional<String> normalizedAlias = normalizeForResolution(candidateNode.path("matchedAlias"));
      String aliasLookup = normalizedAlias.orElse(normalizedCanonical.get());

      List<TagResolution> matches =
          db.sql(
                  """
                  select t.id, t.canonical_name, cast(null as varchar) as matched_alias
                    from tags t
                   where t.owner_id = :ownerId
                     and t.state = 'ACTIVE'
                     and (t.normalized_name = :canonical or t.normalized_name = :alias)
                  union all
                  select t.id, t.canonical_name, ta.alias as matched_alias
                    from tag_aliases ta
                    join tags t
                      on t.id = ta.tag_id
                     and t.owner_id = ta.owner_id
                   where ta.owner_id = :ownerId
                     and t.state = 'ACTIVE'
                     and (ta.normalized_alias = :canonical or ta.normalized_alias = :alias)
                  """)
              .param("ownerId", ownerId)
              .param("canonical", normalizedCanonical.get())
              .param("alias", aliasLookup)
              .query(
                  (resultSet, rowNumber) ->
                      new TagResolution(
                          resultSet.getObject("id", UUID.class),
                          resultSet.getString("canonical_name"),
                          resultSet.getString("matched_alias")))
              .list();

      LinkedHashMap<UUID, TagResolution> uniqueMatches = new LinkedHashMap<>();
      for (TagResolution match : matches) {
        uniqueMatches.merge(
            match.id(), match, (first, second) -> second.matchedAlias() == null ? first : second);
      }
      if (uniqueMatches.size() != 1) {
        continue;
      }

      TagResolution resolved = uniqueMatches.values().iterator().next();
      ObjectNode candidate = (ObjectNode) candidateNode;
      candidate
          .put("existingTagId", resolved.id().toString())
          .put("canonicalName", resolved.canonicalName())
          .put("isNewProposal", false);
      if (resolved.matchedAlias() == null) {
        candidate.putNull("matchedAlias");
      } else {
        candidate.put("matchedAlias", resolved.matchedAlias());
      }
    }
  }

  private Optional<String> normalizeForResolution(JsonNode value) {
    if (!value.isTextual()) {
      return Optional.empty();
    }
    try {
      return Optional.of(tagNormalizer.normalize(value.asText()).normalizedName());
    } catch (DomainException exception) {
      return Optional.empty();
    }
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

  private record TagResolution(UUID id, String canonicalName, String matchedAlias) {}

  private record CloudConsentState(boolean granted, String policyVersion, Timestamp grantedAt) {}

  private record CloudEnrichment(ObjectNode proposal, CloudRunEvidence evidence) {}

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
      String executorBindingId,
      int callTimeoutMs,
      int maxAttempts,
      Instant deadlineAt,
      String dispatchState,
      long fenceToken,
      Instant leaseExpiresAt,
      Instant finalizedAt) {

    private Instant acceptedConsentGrantedAt() {
      return evidence.acceptedConsentGrantedAt();
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

    private static CloudRunEvidence pending(CloudAnalysisRequest request) {
      CloudGatewayDescriptor descriptor = request.descriptor();
      return new CloudRunEvidence(
          descriptor.transferMode().name(),
          descriptor.gatewayVersion(),
          descriptor.providerId(),
          descriptor.modelVersion(),
          descriptor.consentPolicyVersion(),
          CloudAnalysisOutcome.PENDING,
          DURABLE_EXECUTION_CONTRACT_VERSION,
          request.authorizationCheckedAt().orElse(null),
          request.acceptedConsentGrantedAt().orElse(null),
          request.providerRequestToken());
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
    private static final List<String> SUPPORTED_SCHEMA_VERSIONS = List.of("1", "2");
    private static final java.util.Set<String> RECOVERABLE =
        java.util.Set.of("REVIEW_REQUIRED", "POSTPONED");
    private static final java.util.Set<String> REJECTABLE =
        java.util.Set.of("REVIEW_REQUIRED", "POSTPONED", "REJECTED");
    private static final java.util.Set<String> POSTPONABLE =
        java.util.Set.of("REVIEW_REQUIRED", "POSTPONED");

    private SetLike() {}
  }
}
