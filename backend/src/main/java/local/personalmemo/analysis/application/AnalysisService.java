package local.personalmemo.analysis.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.analysis.api.AnalysisDtos.ProposalRecoveryView;
import local.personalmemo.analysis.api.AnalysisDtos.ReviewDispositionView;
import local.personalmemo.analysis.api.AnalysisDtos.RunView;
import local.personalmemo.analysis.api.AnalysisDtos.Start;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.LocalAnalyzer;
import local.personalmemo.common.DevIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.idempotency.IdempotencyService;
import local.personalmemo.common.security.Hashing;
import local.personalmemo.memo.application.MemoService;
import local.personalmemo.memo.domain.MemoSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AnalysisService {
  private static final String START_OPERATION = "ANALYSIS_START";
  private static final String REJECT_OPERATION = "ANALYSIS_REJECT";
  private static final String POSTPONE_OPERATION = "ANALYSIS_POSTPONE";
  private static final int MAX_RECOVERY_PROPOSALS = 100;

  private final JdbcClient db;
  private final DevIdentity identity;
  private final MemoService memos;
  private final LocalAnalyzer analyzer;
  private final AnalysisProposalValidator proposalValidator;
  private final IdempotencyService idempotency;
  private final ObjectMapper json;

  public AnalysisService(
      JdbcClient db,
      DevIdentity identity,
      MemoService memos,
      LocalAnalyzer analyzer,
      AnalysisProposalValidator proposalValidator,
      IdempotencyService idempotency,
      ObjectMapper json) {
    this.db = db;
    this.identity = identity;
    this.memos = memos;
    this.analyzer = analyzer;
    this.proposalValidator = proposalValidator;
    this.idempotency = idempotency;
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
    ObjectNode proposal =
        analyzer.analyze(
            memoId,
            request.memoRevision(),
            memo.content(),
            memo.clientRecordedAt(),
            memo.sourceTimeZone());
    proposalValidator.validate(proposal, memoId, request.memoRevision(), memo.content().length());
    validateProposalReferences(proposal);

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
              ambiguity_reasons,
              created_at,
              completed_at
            ) values (
              :runId,
              :ownerId,
              :memoId,
              :memoRevision,
              'MOCK',
              'REVIEW_REQUIRED',
              '1',
              'fake-v1',
              cast(:ambiguityReasons as jsonb),
              :now,
              :now
            )
            """)
        .param("runId", runId)
        .param("ownerId", identity.ownerId())
        .param("memoId", memoId)
        .param("memoRevision", request.memoRevision())
        .param("ambiguityReasons", proposal.path("ambiguityReasons").toString())
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
  public JsonNode proposal(UUID proposalId) {
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
    return parse(proposalJson);
  }

  @Transactional(readOnly = true)
  public List<ProposalRecoveryView> recoveryProposals(String status, int requestedLimit) {
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
                    parse(resultSet.getString("proposal_json"))))
        .list();
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
              "INVALID_ANALYSIS_PROPOSAL",
              "A proposed relation references an unavailable memo.");
        }
      }
    }
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

  private static final class SetLike {
    private static final java.util.Set<String> RECOVERABLE =
        java.util.Set.of("REVIEW_REQUIRED", "POSTPONED");
    private static final java.util.Set<String> REJECTABLE =
        java.util.Set.of("REVIEW_REQUIRED", "POSTPONED", "REJECTED");
    private static final java.util.Set<String> POSTPONABLE =
        java.util.Set.of("REVIEW_REQUIRED", "POSTPONED");

    private SetLike() {}
  }
}
