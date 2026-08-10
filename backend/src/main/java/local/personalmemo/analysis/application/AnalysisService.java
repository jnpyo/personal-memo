package local.personalmemo.analysis.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
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
import org.springframework.transaction.annotation.Transactional;
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
  private static final int MAX_RECOVERY_PROPOSALS = 100;
  private static final Set<String> RESERVED_CLOUD_METADATA =
      Set.of(
          "toolCalls",
          "cloudTransferMode",
          "cloudGatewayVersion",
          "cloudProviderId",
          "cloudModelVersion",
          "cloudConsentPolicyVersion",
          "cloudOutcome",
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
  private final DeterministicAmbiguityGate ambiguityGate;
  private final AnalysisProposalSchemaValidator proposalSchemaValidator;
  private final AnalysisProposalValidator proposalValidator;
  private final IdempotencyService idempotency;
  private final TagNormalizer tagNormalizer;
  private final ObjectMapper json;

  public AnalysisService(
      JdbcClient db,
      CurrentIdentity identity,
      MemoService memos,
      LocalAnalyzer analyzer,
      CloudAnalysisGateway cloudGateway,
      DeterministicAmbiguityGate ambiguityGate,
      AnalysisProposalSchemaValidator proposalSchemaValidator,
      AnalysisProposalValidator proposalValidator,
      IdempotencyService idempotency,
      TagNormalizer tagNormalizer,
      ObjectMapper json) {
    this.db = db;
    this.identity = identity;
    this.memos = memos;
    this.analyzer = analyzer;
    this.cloudGateway = cloudGateway;
    this.ambiguityGate = ambiguityGate;
    this.proposalSchemaValidator = proposalSchemaValidator;
    this.proposalValidator = proposalValidator;
    this.idempotency = idempotency;
    this.tagNormalizer = tagNormalizer;
    this.json = json;
  }

  @Transactional
  public RunView start(UUID memoId, String key, Start request) {
    String requestHash = idempotency.hashRequest(new StartRequest(memoId, request));
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(START_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), RunView.class);
    }

    MemoSnapshot memo = memos.getCurrentForUpdate(memoId);
    requireActiveCurrentRevision(memo, request.memoRevision());

    UUID runId = UUID.randomUUID();
    UUID proposalId = UUID.randomUUID();
    Instant now = Instant.now();
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
    validateProposal(localProposal, memo, proposalSchemaVersion, provenance, routingPolicyVersion);
    List<AmbiguityReason> routingReasons = ambiguityGate.routingSignals(localProposal);
    AnalysisRoute route = ambiguityGate.route(routingReasons);
    CloudEnrichment enrichment =
        route == AnalysisRoute.LOCAL_REVIEW
            ? new CloudEnrichment(localProposal, CloudRunEvidence.notRequired())
            : enrichWithCloud(
                new CloudAnalysisRequest(localProposal, routingReasons, routingPolicyVersion),
                memo,
                proposalSchemaVersion,
                provenance,
                routingPolicyVersion);
    ObjectNode proposal = enrichment.proposal();
    CloudRunEvidence cloud = enrichment.evidence();
    String persistedRoute = route == AnalysisRoute.LOCAL_REVIEW ? "LOCAL" : "HYBRID";

    Timestamp timestamp = Timestamp.from(now);
    db.sql(
            """
            insert into analysis_runs(
              id,
              owner_id,
              memo_id,
              memo_revision,
              route,
              status,
              schema_version,
              analyzer_version,
              prompt_version,
              local_model_version,
              embedding_model_version,
              routing_policy_version,
              cloud_transfer_mode,
              cloud_gateway_version,
              cloud_provider_id,
              cloud_model_version,
              cloud_consent_policy_version,
              cloud_outcome,
              ambiguity_reasons,
              created_at,
              completed_at
            ) values (
              :runId,
              :ownerId,
              :memoId,
              :memoRevision,
              :route,
              'REVIEW_REQUIRED',
              :schemaVersion,
              :analyzerVersion,
              :promptVersion,
              :localModelVersion,
              :embeddingModelVersion,
              :routingPolicyVersion,
              :cloudTransferMode,
              :cloudGatewayVersion,
              :cloudProviderId,
              :cloudModelVersion,
              :cloudConsentPolicyVersion,
              :cloudOutcome,
              cast(:ambiguityReasons as jsonb),
              :now,
              :now
            )
            """)
        .param("runId", runId)
        .param("ownerId", identity.ownerId())
        .param("memoId", memoId)
        .param("memoRevision", request.memoRevision())
        .param("route", persistedRoute)
        .param("schemaVersion", proposalSchemaVersion)
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
        .param("ambiguityReasons", serializeAmbiguityReasons(routingReasons))
        .param("now", timestamp)
        .update();
    db.sql(
            """
            insert into analysis_proposals(
              id, owner_id, analysis_run_id, proposal_json, proposal_hash, created_at
            ) values (
              :proposalId,
              :ownerId,
              :runId,
              cast(:proposalJson as jsonb),
              :proposalHash,
              :now
            )
            """)
        .param("proposalId", proposalId)
        .param("ownerId", identity.ownerId())
        .param("runId", runId)
        .param("proposalJson", proposal.toString())
        .param("proposalHash", Hashing.sha256(proposal.toString()))
        .param("now", timestamp)
        .update();

    RunView response =
        new RunView(runId, memoId, request.memoRevision(), "REVIEW_REQUIRED", proposalId);
    idempotency.store(START_OPERATION, key, requestHash, runId, response);
    return response;
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

  private void validateProposalReferences(JsonNode proposal) {
    for (JsonNode tag : proposal.path("tagCandidates")) {
      if (tag.path("existingTagId").isTextual()) {
        requireOwnedActiveTag(UUID.fromString(tag.path("existingTagId").asText()));
      }
    }
    for (JsonNode relation : proposal.path("relationCandidates")) {
      UUID targetId = UUID.fromString(relation.path("targetId").asText());
      if ("TAG".equals(relation.path("targetType").asText())) {
        requireOwnedActiveTag(targetId);
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
                .param("ownerId", identity.ownerId())
                .query(Boolean.class)
                .single();
        if (!exists) {
          throw DomainException.invalid(
              "INVALID_ANALYSIS_PROPOSAL", "A proposed relation references an unavailable memo.");
        }
      }
    }
  }

  private void validateProposal(
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
    resolveOwnerScopedTagCandidates(proposal);
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
    validateProposalReferences(proposal);
  }

  /**
   * Converts only structurally valid, explicitly new tag candidates into canonical references. The
   * analyzer has no owner context, so it must never invent persistence identifiers. A candidate is
   * resolved only when its normalized canonical/alias values identify exactly one active tag owned
   * by the authenticated user.
   */
  private void resolveOwnerScopedTagCandidates(ObjectNode proposal) {
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
              .param("ownerId", identity.ownerId())
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

  private CloudEnrichment enrichWithCloud(
      CloudAnalysisRequest request,
      MemoSnapshot memo,
      String expectedSchemaVersion,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion) {
    CloudGatewayDescriptor descriptor;
    try {
      descriptor = cloudGateway.descriptor();
      if (descriptor == null) {
        return validatedFallback(
            request,
            memo,
            expectedSchemaVersion,
            expectedProvenance,
            expectedRoutingPolicyVersion,
            CloudRunEvidence.descriptorUnavailable(CloudAnalysisOutcome.UNEXPECTED_FAILURE));
      }
    } catch (RuntimeException exception) {
      return validatedFallback(
          request,
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion,
          CloudRunEvidence.descriptorUnavailable(CloudAnalysisOutcome.UNEXPECTED_FAILURE));
    }

    if (descriptor.transferMode() == CloudTransferMode.EXTERNAL_MEMO_CONTENT
        && !hasPinnedCloudConsent(descriptor.consentPolicyVersion(), Instant.now())) {
      return validatedFallback(
          request,
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion,
          CloudRunEvidence.from(descriptor, CloudAnalysisOutcome.CONSENT_REQUIRED));
    }

    CloudAnalysisResult result;
    try {
      result = cloudGateway.enrich(request);
    } catch (RuntimeException exception) {
      return validatedFallback(
          request,
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion,
          CloudRunEvidence.from(descriptor, CloudAnalysisOutcome.UNEXPECTED_FAILURE));
    }

    if (result instanceof CloudAnalysisResult.Failure failure) {
      return validatedFallback(
          request,
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion,
          CloudRunEvidence.from(descriptor, outcomeFor(failure.reason())));
    }
    if (!(result instanceof CloudAnalysisResult.Success success)) {
      return validatedFallback(
          request,
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion,
          CloudRunEvidence.from(descriptor, CloudAnalysisOutcome.INVALID_RESPONSE));
    }

    ObjectNode enriched = success.proposal();
    CloudRunEvidence successEvidence =
        CloudRunEvidence.from(descriptor, CloudAnalysisOutcome.SUCCESS);
    try {
      validateProposal(
          enriched, memo, expectedSchemaVersion, expectedProvenance, expectedRoutingPolicyVersion);
      canonicalizeProviderMetadata(enriched, request.validatedLocalProposal());
      stampCloudMetadata(enriched, request, successEvidence);
      validateProposal(
          enriched, memo, expectedSchemaVersion, expectedProvenance, expectedRoutingPolicyVersion);
      return new CloudEnrichment(enriched, successEvidence);
    } catch (RuntimeException exception) {
      return validatedFallback(
          request,
          memo,
          expectedSchemaVersion,
          expectedProvenance,
          expectedRoutingPolicyVersion,
          CloudRunEvidence.from(descriptor, CloudAnalysisOutcome.INVALID_RESPONSE));
    }
  }

  private CloudEnrichment validatedFallback(
      CloudAnalysisRequest request,
      MemoSnapshot memo,
      String expectedSchemaVersion,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion,
      CloudRunEvidence evidence) {
    ObjectNode fallback = request.validatedLocalProposal();
    canonicalizeProviderMetadata(fallback, fallback);
    stampCloudMetadata(fallback, request, evidence);
    validateProposal(
        fallback, memo, expectedSchemaVersion, expectedProvenance, expectedRoutingPolicyVersion);
    return new CloudEnrichment(fallback, evidence);
  }

  private CloudAnalysisOutcome outcomeFor(CloudAnalysisFailureReason reason) {
    return switch (reason) {
      case UNAVAILABLE -> CloudAnalysisOutcome.UNAVAILABLE;
      case TIMEOUT -> CloudAnalysisOutcome.TIMEOUT;
      case RETRY_EXHAUSTED -> CloudAnalysisOutcome.RETRY_EXHAUSTED;
      case PROVIDER_ERROR -> CloudAnalysisOutcome.PROVIDER_ERROR;
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

  private boolean hasPinnedCloudConsent(String consentPolicyVersion, Instant authorizationInstant) {
    return db.sql(
            """
            select cloud_analysis_consent,
                   cloud_analysis_consent_policy_version,
                   cloud_analysis_consent_granted_at
              from user_settings
             where user_id = :ownerId
             for share
            """)
        .param("ownerId", identity.ownerId())
        .query(
            (resultSet, rowNumber) ->
                resultSet.getBoolean("cloud_analysis_consent")
                    && consentPolicyVersion.equals(
                        resultSet.getString("cloud_analysis_consent_policy_version"))
                    && wasGrantedBy(
                        resultSet.getTimestamp("cloud_analysis_consent_granted_at"),
                        authorizationInstant))
        .optional()
        .orElse(false);
  }

  private boolean wasGrantedBy(Timestamp grantedAt, Instant authorizationInstant) {
    return grantedAt != null && !grantedAt.toInstant().isAfter(authorizationInstant);
  }

  private void stampCloudMetadata(
      ObjectNode proposal, CloudAnalysisRequest request, CloudRunEvidence evidence) {
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
    metadata.put("receivedRoutingPolicyVersion", request.routingPolicyVersion());
    var receivedReasons = metadata.putArray("receivedRoutingReasons");
    request.routingReasons().forEach(reason -> receivedReasons.add(reason.name()));
  }

  private String serializeAmbiguityReasons(List<AmbiguityReason> reasons) {
    var values = json.createArrayNode();
    for (AmbiguityReason reason : reasons) {
      values.add(reason.name());
    }
    return values.toString();
  }

  private void requireOwnedActiveTag(UUID tagId) {
    boolean exists =
        db.sql(
                """
                select exists(
                  select 1
                    from tags
                   where id = :tagId
                     and owner_id = :ownerId
                     and state = 'ACTIVE'
                )
                """)
            .param("tagId", tagId)
            .param("ownerId", identity.ownerId())
            .query(Boolean.class)
            .single();
    if (!exists) {
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

  private record CloudEnrichment(ObjectNode proposal, CloudRunEvidence evidence) {}

  private record CloudRunEvidence(
      String transferMode,
      String gatewayVersion,
      String providerId,
      String modelVersion,
      String consentPolicyVersion,
      CloudAnalysisOutcome outcome) {

    private static CloudRunEvidence notRequired() {
      return new CloudRunEvidence(
          "NOT_REQUIRED", "none", "none", "none", "none", CloudAnalysisOutcome.NOT_REQUIRED);
    }

    private static CloudRunEvidence descriptorUnavailable(CloudAnalysisOutcome outcome) {
      return new CloudRunEvidence(
          "DESCRIPTOR_UNAVAILABLE",
          "unavailable",
          "unavailable",
          "unavailable",
          "unavailable",
          outcome);
    }

    private static CloudRunEvidence from(
        CloudGatewayDescriptor descriptor, CloudAnalysisOutcome outcome) {
      return new CloudRunEvidence(
          descriptor.transferMode().name(),
          descriptor.gatewayVersion(),
          descriptor.providerId(),
          descriptor.modelVersion(),
          descriptor.consentPolicyVersion(),
          outcome);
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
