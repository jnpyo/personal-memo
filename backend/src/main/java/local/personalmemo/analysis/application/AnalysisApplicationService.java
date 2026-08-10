package local.personalmemo.analysis.application;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.analysis.api.AnalysisDtos.ApplicationRecoveryView;
import local.personalmemo.analysis.api.AnalysisDtos.ApplicationView;
import local.personalmemo.analysis.api.AnalysisDtos.Apply;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedApply;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedDue;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedItem;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedTag;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.idempotency.IdempotencyService;
import local.personalmemo.memo.application.MemoService;
import local.personalmemo.memo.domain.MemoSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisApplicationService {
  private static final String APPLY_OPERATION = "ANALYSIS_APPLY";
  private static final String UNDO_OPERATION = "ANALYSIS_UNDO";

  private final JdbcClient db;
  private final CurrentIdentity identity;
  private final MemoService memos;
  private final AnalysisApplicationValidator validator;
  private final IdempotencyService idempotency;
  private final ObjectMapper json;

  public AnalysisApplicationService(
      JdbcClient db,
      CurrentIdentity identity,
      MemoService memos,
      AnalysisApplicationValidator validator,
      IdempotencyService idempotency,
      ObjectMapper json) {
    this.db = db;
    this.identity = identity;
    this.memos = memos;
    this.validator = validator;
    this.idempotency = idempotency;
    this.json = json;
  }

  @Transactional
  public ApplicationView apply(UUID proposalId, String key, Apply request) {
    String requestHash = idempotency.hashRequest(new ApplyRequest(proposalId, request));
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(APPLY_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), ApplicationView.class);
    }

    acquireOwnerApplicationLock();
    ValidatedApply selection = validator.validate(request);
    ProposalRun observedProposal = findProposalRun(proposalId, false);
    MemoSnapshot memo = memos.getCurrentForUpdate(observedProposal.memoId());
    ProposalRun proposal = findProposalRun(proposalId, true);
    requireSameProposalIdentity(observedProposal, proposal);
    selection = validator.canonicalizeDueTimeZone(selection, memo.sourceTimeZone());
    ensureApplicable(proposal, memo, selection.expectedMemoRevision());
    rejectUnsupportedRelationCandidates(proposal);

    UUID applicationId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    db.sql(
            """
            insert into analysis_applications(
              id,
              owner_id,
              proposal_id,
              memo_id,
              memo_revision,
              idempotency_key,
              status,
              selection_json,
              applied_at,
              undone_at
            ) values (
              :applicationId,
              :ownerId,
              :proposalId,
              :memoId,
              :memoRevision,
              :idempotencyKey,
              'APPLIED',
              cast(:selectionJson as jsonb),
              :appliedAt,
              null
            )
            """)
        .param("applicationId", applicationId)
        .param("ownerId", identity.ownerId())
        .param("proposalId", proposalId)
        .param("memoId", proposal.memoId())
        .param("memoRevision", proposal.memoRevision())
        .param("idempotencyKey", key)
        .param("selectionJson", write(selection))
        .param("appliedAt", now)
        .update();

    List<UUID> selectedTagIds = createOrValidateTags(selection.selectedTags(), applicationId, now);
    for (ValidatedItem item : selection.items()) {
      UUID itemId = createMemoItem(item, proposal, applicationId, now);
      if ("TASK".equals(item.kind())) {
        createTaskDetails(itemId, item.due());
      }
      linkTags(itemId, selectedTagIds, applicationId, now);
    }

    db.sql(
            """
            update analysis_runs
               set status = 'APPLIED'
             where id = :runId
               and owner_id = :ownerId
               and status in ('REVIEW_REQUIRED', 'POSTPONED')
            """)
        .param("runId", proposal.runId())
        .param("ownerId", identity.ownerId())
        .update();

    ApplicationView response = new ApplicationView(applicationId, "APPLIED");
    idempotency.store(APPLY_OPERATION, key, requestHash, applicationId, response);
    return response;
  }

  @Transactional
  public ApplicationView undo(UUID applicationId, String key) {
    String requestHash = idempotency.hashRequest(new UndoRequest(applicationId));
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(UNDO_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), ApplicationView.class);
    }

    acquireOwnerApplicationLock();
    ApplicationRecord application = findApplicationForUpdate(applicationId);
    if ("APPLIED".equals(application.status())) {
      MemoSnapshot memo = memos.getCurrentForUpdate(application.memoId());
      reverseDerivedRecords(application, memo, Timestamp.from(Instant.now()));
    } else if (!"UNDONE".equals(application.status())) {
      throw DomainException.conflict(
          "APPLICATION_NOT_UNDOABLE", "The analysis application cannot be undone.");
    }

    ApplicationView response = new ApplicationView(applicationId, "UNDONE");
    idempotency.store(UNDO_OPERATION, key, requestHash, applicationId, response);
    return response;
  }

  @Transactional(readOnly = true)
  public ApplicationRecoveryView latest() {
    return db.sql(
            """
            select id, status
              from analysis_applications
             where owner_id = :ownerId
             order by applied_at desc, id desc
             limit 1
            """)
        .param("ownerId", identity.ownerId())
        .query(
            (resultSet, rowNumber) ->
                new ApplicationRecoveryView(
                    resultSet.getObject("id", UUID.class), resultSet.getString("status")))
        .optional()
        .orElseGet(() -> new ApplicationRecoveryView(null, "NONE"));
  }

  private List<UUID> createOrValidateTags(
      List<ValidatedTag> selectedTags, UUID applicationId, Timestamp now) {
    List<UUID> tagIds = new ArrayList<>();
    for (ValidatedTag tag : selectedTags) {
      if (tag.existingTagId() != null) {
        UUID existingTag = findOwnedActiveTagForShare(tag.existingTagId());
        tagIds.add(existingTag);
        continue;
      }

      Optional<UUID> createdTag =
          db.sql(
                  """
                  insert into tags(
                    id,
                    owner_id,
                    canonical_name,
                    normalized_name,
                    state,
                    created_at,
                    updated_at,
                    version,
                    created_by_application_id
                  ) values (
                    :tagId,
                    :ownerId,
                    :canonicalName,
                    :normalizedName,
                    'ACTIVE',
                    :now,
                    :now,
                    0,
                    :applicationId
                  )
                  on conflict (owner_id, normalized_name) do nothing
                  returning id
                  """)
              .param("tagId", UUID.randomUUID())
              .param("ownerId", identity.ownerId())
              .param("canonicalName", tag.newCanonicalName())
              .param("normalizedName", tag.normalizedName())
              .param("now", now)
              .param("applicationId", applicationId)
              .query(UUID.class)
              .optional();
      if (createdTag.isEmpty()) {
        throw DomainException.conflict(
            "TAG_ALREADY_EXISTS", "A canonical tag with the same normalized name already exists.");
      }
      tagIds.add(createdTag.get());
    }
    return List.copyOf(tagIds);
  }

  private UUID findOwnedActiveTagForShare(UUID tagId) {
    return db.sql(
            """
            select id
              from tags
             where id = :tagId
               and owner_id = :ownerId
               and state = 'ACTIVE'
             for share
            """)
        .param("tagId", tagId)
        .param("ownerId", identity.ownerId())
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> DomainException.notFound("Tag"));
  }

  private UUID createMemoItem(
      ValidatedItem item, ProposalRun proposal, UUID applicationId, Timestamp now) {
    UUID itemId = UUID.randomUUID();
    db.sql(
            """
            insert into memo_items(
              id,
              owner_id,
              memo_id,
              memo_revision,
              application_id,
              kind,
              title,
              created_at
            ) values (
              :itemId,
              :ownerId,
              :memoId,
              :memoRevision,
              :applicationId,
              :kind,
              :title,
              :now
            )
            """)
        .param("itemId", itemId)
        .param("ownerId", identity.ownerId())
        .param("memoId", proposal.memoId())
        .param("memoRevision", proposal.memoRevision())
        .param("applicationId", applicationId)
        .param("kind", item.kind())
        .param("title", item.title())
        .param("now", now)
        .update();
    return itemId;
  }

  private void createTaskDetails(UUID itemId, ValidatedDue due) {
    Timestamp dueAt =
        due == null || due.dueInstant() == null ? null : Timestamp.from(due.dueInstant());
    Date dueDate =
        due == null || due.dueLocalDate() == null ? null : Date.valueOf(due.dueLocalDate());
    db.sql(
            """
            insert into task_details(
              memo_item_id,
              owner_id,
              status,
              due_at_utc,
              due_local_date,
              date_surface_text,
              date_precision,
              source_time_zone,
              time_was_explicit
            ) values (
              :itemId,
              :ownerId,
              'TODO',
              :dueAt,
              :dueDate,
              :surfaceText,
              :precision,
              :timeZone,
              :timeSpecified
            )
            """)
        .param("itemId", itemId)
        .param("ownerId", identity.ownerId())
        .param("dueAt", dueAt)
        .param("dueDate", dueDate)
        .param("surfaceText", due == null ? null : due.surfaceText())
        .param("precision", due == null ? null : due.precision())
        .param("timeZone", due == null ? null : due.timeZone())
        .param("timeSpecified", due != null && due.timeSpecified())
        .update();
  }

  private void linkTags(UUID itemId, List<UUID> tagIds, UUID applicationId, Timestamp confirmedAt) {
    for (UUID tagId : tagIds) {
      db.sql(
              """
              insert into item_tags(
                memo_item_id,
                owner_id,
                tag_id,
                application_id,
                source,
                score,
                confirmed_at
              ) values (
                :itemId, :ownerId, :tagId, :applicationId, 'USER', null, :confirmedAt
              )
              """)
          .param("itemId", itemId)
          .param("ownerId", identity.ownerId())
          .param("tagId", tagId)
          .param("applicationId", applicationId)
          .param("confirmedAt", confirmedAt)
          .update();
    }
  }

  private void reverseDerivedRecords(
      ApplicationRecord application, MemoSnapshot memo, Timestamp undoneAt) {
    db.sql(
            """
            delete from item_tags it
             where it.application_id = :applicationId
               and it.owner_id = :ownerId
            """)
        .param("applicationId", application.id())
        .param("ownerId", identity.ownerId())
        .update();
    db.sql(
            """
            delete from task_details t
             where t.owner_id = :ownerId
               and exists (
                 select 1
                   from memo_items i
                  where i.id = t.memo_item_id
                  and i.application_id = :applicationId
                  and i.owner_id = :ownerId
             )
            """)
        .param("applicationId", application.id())
        .param("ownerId", identity.ownerId())
        .update();
    db.sql(
            """
            delete from memo_items
             where application_id = :applicationId
               and owner_id = :ownerId
            """)
        .param("applicationId", application.id())
        .param("ownerId", identity.ownerId())
        .update();
    db.sql(
            """
            update analysis_applications
               set status = 'UNDONE',
                   undone_at = :undoneAt
             where id = :applicationId
               and owner_id = :ownerId
               and status = 'APPLIED'
            """)
        .param("undoneAt", undoneAt)
        .param("applicationId", application.id())
        .param("ownerId", identity.ownerId())
        .update();
    removeOrphanedTagsFromUndoneApplications();
    db.sql(
            """
            update analysis_runs r
               set status = :restoredStatus
             where r.id = :runId
               and r.owner_id = :ownerId
            """)
        .param(
            "restoredStatus",
            memo.isActive() && memo.currentRevision() == application.memoRevision()
                ? "REVIEW_REQUIRED"
                : "STALE")
        .param("runId", application.runId())
        .param("ownerId", identity.ownerId())
        .update();
  }

  private void acquireOwnerApplicationLock() {
    String lockScope = identity.ownerId() + ":ANALYSIS_APPLICATION_OWNER";
    db.sql("select pg_advisory_xact_lock(hashtextextended(:lockScope, 0))")
        .param("lockScope", lockScope)
        .query(
            (resultSet, rowNumber) -> {
              resultSet.getObject(1);
              return rowNumber;
            })
        .single();
  }

  private void removeOrphanedTagsFromUndoneApplications() {
    db.sql(
            """
            delete from tags t
             where t.owner_id = :ownerId
               and t.created_by_application_id is not null
               and exists (
                 select 1
                   from analysis_applications creator
                  where creator.id = t.created_by_application_id
                    and creator.owner_id = t.owner_id
                    and creator.status = 'UNDONE'
               )
               and not exists (
                 select 1
                   from item_tags it
                  where it.tag_id = t.id
                    and it.owner_id = t.owner_id
               )
               and not exists (
                 select 1
                   from tag_aliases ta
                  where ta.tag_id = t.id
                    and ta.owner_id = t.owner_id
               )
            """)
        .param("ownerId", identity.ownerId())
        .update();
  }

  private ProposalRun findProposalRun(UUID proposalId, boolean forUpdate) {
    String lockingClause = forUpdate ? " for update of p, r" : "";
    return db.sql(
            """
            select r.id as run_id,
                   r.memo_id,
                   r.memo_revision,
                   r.status,
                   case
                     when jsonb_typeof(p.proposal_json -> 'relationCandidates') = 'array'
                       then jsonb_array_length(p.proposal_json -> 'relationCandidates') > 0
                     else true
                   end as has_relation_candidates
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
        || observed.memoRevision() != locked.memoRevision()
        || observed.hasRelationCandidates() != locked.hasRelationCandidates()) {
      throw DomainException.conflict(
          "PROPOSAL_CHANGED", "The analysis proposal changed while it was being applied.");
    }
  }

  private ProposalRun mapProposalRun(ResultSet resultSet, int rowNumber) throws SQLException {
    return new ProposalRun(
        resultSet.getObject("run_id", UUID.class),
        resultSet.getObject("memo_id", UUID.class),
        resultSet.getInt("memo_revision"),
        resultSet.getString("status"),
        resultSet.getBoolean("has_relation_candidates"));
  }

  private ApplicationRecord findApplicationForUpdate(UUID applicationId) {
    return db.sql(
            """
            select a.id,
                   a.status,
                   a.memo_id,
                   a.memo_revision,
                   r.id as run_id
              from analysis_applications a
              join analysis_proposals p
                on p.id = a.proposal_id
               and p.owner_id = a.owner_id
              join analysis_runs r
                on r.id = p.analysis_run_id
               and r.owner_id = p.owner_id
             where a.id = :applicationId
               and a.owner_id = :ownerId
             for update of a
            """)
        .param("applicationId", applicationId)
        .param("ownerId", identity.ownerId())
        .query(this::mapApplication)
        .optional()
        .orElseThrow(() -> DomainException.notFound("Analysis application"));
  }

  private ApplicationRecord mapApplication(ResultSet resultSet, int rowNumber) throws SQLException {
    return new ApplicationRecord(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("status"),
        resultSet.getObject("memo_id", UUID.class),
        resultSet.getInt("memo_revision"),
        resultSet.getObject("run_id", UUID.class));
  }

  private void ensureApplicable(ProposalRun proposal, MemoSnapshot memo, int expectedMemoRevision) {
    if (!memo.isActive()) {
      throw DomainException.conflict("MEMO_NOT_ACTIVE", "The memo is not active.");
    }
    if (proposal.memoRevision() != expectedMemoRevision
        || memo.currentRevision() != proposal.memoRevision()
        || "STALE".equals(proposal.status())) {
      throw DomainException.conflict(
          "STALE_MEMO_REVISION", "The memo changed after this proposal was created.");
    }
    if (!java.util.Set.of("REVIEW_REQUIRED", "POSTPONED").contains(proposal.status())) {
      throw DomainException.conflict(
          "PROPOSAL_NOT_APPLICABLE", "The analysis proposal is not awaiting review.");
    }
  }

  private void rejectUnsupportedRelationCandidates(ProposalRun proposal) {
    if (proposal.hasRelationCandidates()) {
      throw DomainException.conflict(
          "PROPOSAL_RELATIONS_UNSUPPORTED",
          "Relation candidates require explicit relation selection, which is not supported yet.");
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not serialize the validated selection.", exception);
    }
  }

  private record ApplyRequest(UUID proposalId, Apply request) {}

  private record UndoRequest(UUID applicationId) {}

  private record ProposalRun(
      UUID runId, UUID memoId, int memoRevision, String status, boolean hasRelationCandidates) {}

  private record ApplicationRecord(
      UUID id, String status, UUID memoId, int memoRevision, UUID runId) {}
}
