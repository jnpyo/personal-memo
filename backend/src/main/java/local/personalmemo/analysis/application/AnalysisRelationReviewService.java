package local.personalmemo.analysis.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.analysis.api.AnalysisDtos.RelationReviewCandidate;
import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisRelationReviewService {
  private static final int MAX_RELATION_CANDIDATES = 10;
  private static final int MAX_PREVIEW_CODE_POINTS = 240;

  private final JdbcClient db;
  private final CurrentIdentity identity;
  private final ObjectMapper json;
  private final AnalysisProposalSchemaValidator proposalSchemaValidator;
  private final AnalysisProposalValidator proposalValidator;

  public AnalysisRelationReviewService(
      JdbcClient db,
      CurrentIdentity identity,
      ObjectMapper json,
      AnalysisProposalSchemaValidator proposalSchemaValidator,
      AnalysisProposalValidator proposalValidator) {
    this.db = db;
    this.identity = identity;
    this.json = json;
    this.proposalSchemaValidator = proposalSchemaValidator;
    this.proposalValidator = proposalValidator;
  }

  @Transactional(readOnly = true)
  public List<RelationReviewCandidate> candidates(UUID proposalId) {
    StoredProposal storedProposal =
        db.sql(
                """
                select proposal.proposal_json::text as proposal_json,
                       run.memo_id,
                       run.memo_revision,
                       revision.content
                  from analysis_proposals proposal
                  join analysis_runs run
                    on run.id = proposal.analysis_run_id
                   and run.owner_id = proposal.owner_id
                  join memo_revisions revision
                    on revision.memo_id = run.memo_id
                   and revision.revision = run.memo_revision
                   and revision.owner_id = run.owner_id
                 where proposal.id = :proposalId
                   and proposal.owner_id = :ownerId
                """)
            .param("proposalId", proposalId)
            .param("ownerId", identity.ownerId())
            .query(
                (resultSet, rowNumber) ->
                    new StoredProposal(
                        resultSet.getString("proposal_json"),
                        resultSet.getObject("memo_id", UUID.class),
                        resultSet.getInt("memo_revision"),
                        resultSet.getString("content")))
            .optional()
            .orElseThrow(() -> DomainException.notFound("Analysis proposal"));
    JsonNode proposal = read(storedProposal.json());
    validateStoredProposal(proposal, storedProposal);
    JsonNode relations = proposal.path("relationCandidates");
    if (!relations.isArray() || relations.size() > MAX_RELATION_CANDIDATES) {
      throw proposalChanged();
    }

    Map<RelationTarget, TargetAvailability> targetCache = new HashMap<>();
    List<RelationReviewCandidate> response = new ArrayList<>();
    for (int proposalIndex = 0; proposalIndex < relations.size(); proposalIndex++) {
      JsonNode relation = relations.get(proposalIndex);
      if (!relation.isObject()) {
        throw proposalChanged();
      }
      String targetType = requiredText(relation, "targetType");
      UUID targetId = requiredUuid(relation, "targetId");
      RelationTarget target = new RelationTarget(targetType, targetId);
      TargetAvailability availability =
          targetCache.computeIfAbsent(target, this::findTargetAvailability);
      response.add(
          new RelationReviewCandidate(
              proposalIndex, targetType, targetId, availability.label(), availability.available()));
    }
    return List.copyOf(response);
  }

  private void validateStoredProposal(JsonNode proposal, StoredProposal storedProposal) {
    try {
      proposalSchemaValidator.validate(proposal);
      proposalValidator.validate(
          proposal,
          storedProposal.memoId(),
          storedProposal.memoRevision(),
          storedProposal.content().length());
    } catch (DomainException exception) {
      throw proposalChanged();
    }
  }

  private TargetAvailability findTargetAvailability(RelationTarget target) {
    return switch (target.targetType()) {
      case "MEMO" -> findActiveMemo(target.targetId());
      case "TAG" -> findActiveTag(target.targetId());
      default -> throw proposalChanged();
    };
  }

  private TargetAvailability findActiveMemo(UUID memoId) {
    Optional<String> content =
        db.sql(
                """
                select revision.content
                  from memos memo
                  join memo_revisions revision
                    on revision.memo_id = memo.id
                   and revision.owner_id = memo.owner_id
                   and revision.revision = memo.current_revision
                 where memo.id = :memoId
                   and memo.owner_id = :ownerId
                   and memo.status = 'ACTIVE'
                """)
            .param("memoId", memoId)
            .param("ownerId", identity.ownerId())
            .query(String.class)
            .optional();
    return content
        .map(value -> new TargetAvailability(preview(value), true))
        .orElseGet(TargetAvailability::unavailable);
  }

  private TargetAvailability findActiveTag(UUID tagId) {
    Optional<String> canonicalName =
        db.sql(
                """
                select canonical_name
                  from tags
                 where id = :tagId
                   and owner_id = :ownerId
                   and state = 'ACTIVE'
                """)
            .param("tagId", tagId)
            .param("ownerId", identity.ownerId())
            .query(String.class)
            .optional();
    return canonicalName
        .map(value -> new TargetAvailability(value, true))
        .orElseGet(TargetAvailability::unavailable);
  }

  private String preview(String content) {
    int codePoints = content.codePointCount(0, content.length());
    if (codePoints <= MAX_PREVIEW_CODE_POINTS) {
      return content;
    }
    int end = content.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS - 1);
    return content.substring(0, end) + "…";
  }

  private JsonNode read(String value) {
    try {
      return json.readTree(value);
    } catch (Exception exception) {
      throw proposalChanged();
    }
  }

  private String requiredText(JsonNode object, String field) {
    JsonNode value = object.path(field);
    if (!value.isTextual()) {
      throw proposalChanged();
    }
    return value.asText();
  }

  private UUID requiredUuid(JsonNode object, String field) {
    try {
      return UUID.fromString(requiredText(object, field));
    } catch (IllegalArgumentException exception) {
      throw proposalChanged();
    }
  }

  private DomainException proposalChanged() {
    return DomainException.conflict(
        "PROPOSAL_CHANGED", "The analysis proposal changed while it was being reviewed.");
  }

  private record RelationTarget(String targetType, UUID targetId) {}

  private record StoredProposal(String json, UUID memoId, int memoRevision, String content) {}

  private record TargetAvailability(String label, boolean available) {
    private static TargetAvailability unavailable() {
      return new TargetAvailability(null, false);
    }
  }
}
