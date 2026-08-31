package local.personalmemo.analysis.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.api.AnalysisDtos.ApplicationRecoveryView;
import local.personalmemo.analysis.api.AnalysisDtos.ApplicationView;
import local.personalmemo.analysis.api.AnalysisDtos.Apply;
import local.personalmemo.analysis.api.AnalysisDtos.Due;
import local.personalmemo.analysis.api.AnalysisDtos.EventSchedule;
import local.personalmemo.analysis.api.AnalysisDtos.Item;
import local.personalmemo.analysis.api.AnalysisDtos.SelectedRelation;
import local.personalmemo.analysis.api.AnalysisDtos.Tag;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedApply;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedDue;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedEventSchedule;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedItem;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedSelectedRelation;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator.ValidatedTag;
import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.calendar.application.CalendarFeedProjectionService;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.idempotency.IdempotencyService;
import local.personalmemo.memo.application.MemoService;
import local.personalmemo.memo.domain.MemoSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisApplicationService {
  private static final String APPLY_OPERATION = "ANALYSIS_APPLY";
  private static final String UNDO_OPERATION = "ANALYSIS_UNDO";
  private static final Set<String> RELATION_TARGET_TYPES = Set.of("MEMO", "TAG");
  private static final Set<String> RELATION_TYPES =
      Set.of("RELATED_TO", "CONTINUES", "DEPENDS_ON", "REFERENCES");

  private final JdbcClient db;
  private final CurrentIdentity identity;
  private final MemoService memos;
  private final AnalysisApplicationValidator validator;
  private final AnalysisProposalSchemaValidator proposalSchemaValidator;
  private final AnalysisProposalValidator proposalValidator;
  private final IdempotencyService idempotency;
  private final ObjectMapper json;
  private final CalendarFeedProjectionService calendarFeeds;

  public AnalysisApplicationService(
      JdbcClient db,
      CurrentIdentity identity,
      MemoService memos,
      AnalysisApplicationValidator validator,
      AnalysisProposalSchemaValidator proposalSchemaValidator,
      AnalysisProposalValidator proposalValidator,
      IdempotencyService idempotency,
      ObjectMapper json,
      CalendarFeedProjectionService calendarFeeds) {
    this.db = db;
    this.identity = identity;
    this.memos = memos;
    this.validator = validator;
    this.proposalSchemaValidator = proposalSchemaValidator;
    this.proposalValidator = proposalValidator;
    this.idempotency = idempotency;
    this.json = json;
    this.calendarFeeds = calendarFeeds;
  }

  @Transactional
  public ApplicationView apply(UUID proposalId, String key, Apply request) {
    String requestHash = idempotency.hashRequest(applyHashMaterial(proposalId, request));
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
    validateLockedProposal(proposal.proposal(), memo);

    UUID applicationId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    List<PlannedItem> plannedItems = planItems(selection.items());
    List<ResolvedRelation> selectedRelations =
        resolveSelectedRelations(selection, proposal.proposal(), plannedItems);
    lockRelationTargets(selectedRelations);
    StoredSelection storedSelection = storedSelection(selection, plannedItems, selectedRelations);
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
        .param("selectionJson", write(storedSelection))
        .param("appliedAt", now)
        .update();

    List<UUID> selectedTagIds = createOrValidateTags(selection.selectedTags(), applicationId, now);
    for (PlannedItem plannedItem : plannedItems) {
      createMemoItem(plannedItem.id(), plannedItem.item(), proposal, applicationId, now);
      if ("TASK".equals(plannedItem.item().kind())) {
        createTaskDetails(plannedItem.id(), plannedItem.item().due());
      }
      if (plannedItem.item().eventSchedule() != null) {
        createEventDetails(plannedItem.id(), plannedItem.item().eventSchedule());
      }
      linkTags(plannedItem.id(), selectedTagIds, applicationId, now);
    }
    persistRelations(selectedRelations, applicationId, now);

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

  private List<PlannedItem> planItems(List<ValidatedItem> items) {
    return items.stream().map(item -> new PlannedItem(UUID.randomUUID(), item)).toList();
  }

  private Object applyHashMaterial(UUID proposalId, Apply request) {
    boolean eventScheduleShape =
        request.selectionSchemaVersion() != null
            || (request.items() != null
                && request.items().stream()
                    .anyMatch(item -> item != null && item.eventSchedule() != null));
    if (eventScheduleShape) {
      return new ApplyRequestV3(
          "EVENT_SCHEDULE_V1", proposalId, projectEventScheduleApply(request));
    }
    boolean legacyShape =
        request.selectedRelations() == null
            && request.items() != null
            && request.items().stream()
                .allMatch(item -> item != null && item.proposalCandidateId() == null);
    if (!legacyShape) {
      return new ApplyRequestV2("RELATION_SELECTION_V1", proposalId, projectRelationApply(request));
    }
    List<LegacyItem> legacyItems =
        request.items().stream()
            .map(item -> new LegacyItem(item.kind(), item.title(), item.due()))
            .toList();
    return new LegacyApplyRequest(
        proposalId,
        new LegacyApply(
            request.expectedMemoRevision(),
            request.selectedType(),
            request.title(),
            request.selectedTags(),
            legacyItems));
  }

  private RelationApply projectRelationApply(Apply request) {
    List<RelationApplyItem> items = null;
    if (request.items() != null) {
      items = new ArrayList<>();
      for (Item item : request.items()) {
        items.add(
            item == null
                ? null
                : new RelationApplyItem(
                    item.proposalCandidateId(), item.kind(), item.title(), item.due()));
      }
    }
    return new RelationApply(
        request.expectedMemoRevision(),
        request.selectedType(),
        request.title(),
        request.selectedTags(),
        items,
        request.selectedRelations());
  }

  private EventScheduleApply projectEventScheduleApply(Apply request) {
    List<EventScheduleApplyItem> items = null;
    if (request.items() != null) {
      items = new ArrayList<>();
      for (Item item : request.items()) {
        items.add(
            item == null
                ? null
                : new EventScheduleApplyItem(
                    item.proposalCandidateId(),
                    item.kind(),
                    item.title(),
                    item.due(),
                    item.eventSchedule()));
      }
    }
    return new EventScheduleApply(
        request.expectedMemoRevision(),
        request.selectedType(),
        request.title(),
        request.selectedTags(),
        items,
        request.selectedRelations(),
        request.selectionSchemaVersion());
  }

  private void validateLockedProposal(JsonNode proposal, MemoSnapshot memo) {
    try {
      proposalSchemaValidator.validate(proposal);
      proposalValidator.validate(
          proposal, memo.id(), memo.currentRevision(), memo.content().length());
    } catch (DomainException exception) {
      throw proposalChanged();
    }
  }

  private List<ResolvedRelation> resolveSelectedRelations(
      ValidatedApply selection, JsonNode proposal, List<PlannedItem> plannedItems) {
    JsonNode proposalItemsNode = proposal.path("itemCandidates");
    JsonNode relationCandidatesNode = proposal.path("relationCandidates");
    if (!proposalItemsNode.isArray() || !relationCandidatesNode.isArray()) {
      throw proposalChanged();
    }

    Map<String, ProposalItemReference> proposalItems = new HashMap<>();
    for (int proposalItemIndex = 0;
        proposalItemIndex < proposalItemsNode.size();
        proposalItemIndex++) {
      JsonNode candidate = proposalItemsNode.get(proposalItemIndex);
      JsonNode candidateIdNode = candidate.path("candidateId");
      if (!candidate.isObject() || !candidateIdNode.isTextual()) {
        throw proposalChanged();
      }
      String candidateId = candidateIdNode.asText();
      if (proposalItems.put(candidateId, new ProposalItemReference(proposalItemIndex)) != null) {
        throw proposalChanged();
      }
    }

    Map<String, PlannedItem> appliedItems = new HashMap<>();
    for (PlannedItem plannedItem : plannedItems) {
      String proposalCandidateId = plannedItem.item().proposalCandidateId();
      if (proposalCandidateId == null) {
        continue;
      }
      if (!proposalItems.containsKey(proposalCandidateId)
          || appliedItems.put(proposalCandidateId, plannedItem) != null) {
        throw DomainException.invalid(
            "INVALID_RELATION_SELECTION",
            "Every proposalCandidateId must identify one locked proposal item candidate.");
      }
    }

    List<ValidatedSelectedRelation> selected = selection.selectedRelations();
    if (!relationCandidatesNode.isEmpty() && selected == null) {
      throw DomainException.invalid(
          "RELATION_SELECTION_REQUIRED",
          "selectedRelations is required when the proposal contains relation candidates.");
    }
    if (selected == null || selected.isEmpty()) {
      return List.of();
    }

    List<ResolvedRelation> resolved = new ArrayList<>();
    Set<RelationIdentity> uniqueRelations = new HashSet<>();
    for (ValidatedSelectedRelation selectionEntry : selected) {
      int proposalIndex = selectionEntry.proposalIndex();
      if (proposalIndex >= relationCandidatesNode.size()) {
        throw invalidRelationSelection();
      }
      JsonNode candidate = relationCandidatesNode.get(proposalIndex);
      if (!candidate.isObject()) {
        throw proposalChanged();
      }
      String sourceCandidateId = requiredText(candidate, "sourceCandidateId");
      ProposalItemReference sourceProposalItem = proposalItems.get(sourceCandidateId);
      if (sourceProposalItem == null) {
        throw proposalChanged();
      }
      PlannedItem sourceItem = appliedItems.get(sourceCandidateId);
      if (sourceItem == null) {
        throw DomainException.invalid(
            "RELATION_SOURCE_NOT_APPLIED",
            "Each selected relation source must map to exactly one applied item.");
      }

      String targetType = requiredText(candidate, "targetType");
      String relationType = requiredText(candidate, "relationType");
      UUID targetId = requiredUuid(candidate, "targetId");
      if (!RELATION_TARGET_TYPES.contains(targetType) || !RELATION_TYPES.contains(relationType)) {
        throw proposalChanged();
      }
      if (!uniqueRelations.add(
          new RelationIdentity(sourceCandidateId, targetType, targetId, relationType))) {
        throw DomainException.invalid(
            "INVALID_RELATION_SELECTION",
            "The same directed relation cannot be selected more than once.");
      }
      resolved.add(
          new ResolvedRelation(
              proposalIndex,
              sourceCandidateId,
              sourceProposalItem.proposalItemIndex(),
              sourceItem.id(),
              targetType,
              targetId,
              relationType));
    }
    return List.copyOf(resolved);
  }

  private void lockRelationTargets(List<ResolvedRelation> relations) {
    List<RelationTarget> targets =
        relations.stream()
            .map(relation -> new RelationTarget(relation.targetType(), relation.targetId()))
            .distinct()
            .sorted(
                Comparator.comparing(RelationTarget::targetType)
                    .thenComparing(target -> target.targetId().toString()))
            .toList();
    for (RelationTarget target : targets) {
      boolean available =
          switch (target.targetType()) {
            case "MEMO" -> lockActiveMemoTarget(target.targetId());
            case "TAG" -> lockActiveTagTarget(target.targetId());
            default -> false;
          };
      if (!available) {
        throw DomainException.conflict(
            "RELATION_TARGET_UNAVAILABLE",
            "A selected relation target is no longer available for this owner.");
      }
    }
  }

  private boolean lockActiveMemoTarget(UUID targetId) {
    return db.sql(
            """
            select id
              from memos
             where id = :targetId
               and owner_id = :ownerId
               and status = 'ACTIVE'
             for share
            """)
        .param("targetId", targetId)
        .param("ownerId", identity.ownerId())
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  private boolean lockActiveTagTarget(UUID targetId) {
    return db.sql(
            """
            select id
              from tags
             where id = :targetId
               and owner_id = :ownerId
               and state = 'ACTIVE'
             for share
            """)
        .param("targetId", targetId)
        .param("ownerId", identity.ownerId())
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  private StoredSelection storedSelection(
      ValidatedApply selection,
      List<PlannedItem> plannedItems,
      List<ResolvedRelation> selectedRelations) {
    List<StoredItem> items =
        plannedItems.stream()
            .map(
                planned ->
                    new StoredItem(
                        planned.item().proposalCandidateId(),
                        planned.id(),
                        planned.item().kind(),
                        planned.item().title(),
                        planned.item().due(),
                        planned.item().eventSchedule()))
            .toList();
    List<StoredRelation> relations =
        selectedRelations.stream()
            .map(
                relation ->
                    new StoredRelation(
                        relation.proposalIndex(),
                        relation.sourceProposalCandidateId(),
                        relation.sourceProposalItemIndex(),
                        relation.sourceMemoItemId(),
                        relation.targetType(),
                        relation.targetId(),
                        relation.relationType()))
            .toList();
    return new StoredSelection(
        selection.expectedMemoRevision(),
        selection.selectedType(),
        selection.title(),
        selection.selectedTags(),
        items,
        relations,
        selection.selectionSchemaVersion());
  }

  private void persistRelations(
      List<ResolvedRelation> relations, UUID applicationId, Timestamp confirmedAt) {
    for (ResolvedRelation relation : relations) {
      db.sql(
              """
              insert into memo_item_relations(
                application_id,
                proposal_relation_index,
                owner_id,
                source_memo_item_id,
                target_type,
                target_memo_id,
                target_tag_id,
                relation_type,
                confirmed_at
              ) values (
                :applicationId,
                :proposalIndex,
                :ownerId,
                :sourceItemId,
                :targetType,
                :targetMemoId,
                :targetTagId,
                :relationType,
                :confirmedAt
              )
              """)
          .param("applicationId", applicationId)
          .param("proposalIndex", relation.proposalIndex())
          .param("ownerId", identity.ownerId())
          .param("sourceItemId", relation.sourceMemoItemId())
          .param("targetType", relation.targetType())
          .param("targetMemoId", "MEMO".equals(relation.targetType()) ? relation.targetId() : null)
          .param("targetTagId", "TAG".equals(relation.targetType()) ? relation.targetId() : null)
          .param("relationType", relation.relationType())
          .param("confirmedAt", confirmedAt)
          .update();
    }
  }

  private String requiredText(JsonNode candidate, String field) {
    JsonNode value = candidate.path(field);
    if (!value.isTextual()) {
      throw proposalChanged();
    }
    return value.asText();
  }

  private UUID requiredUuid(JsonNode candidate, String field) {
    try {
      return UUID.fromString(requiredText(candidate, field));
    } catch (IllegalArgumentException exception) {
      throw proposalChanged();
    }
  }

  private DomainException invalidRelationSelection() {
    return DomainException.invalid(
        "INVALID_RELATION_SELECTION",
        "Every proposalIndex must identify one locked proposal relation candidate.");
  }

  private DomainException proposalChanged() {
    return DomainException.conflict(
        "PROPOSAL_CHANGED", "The analysis proposal changed while it was being applied.");
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

  private void createMemoItem(
      UUID itemId, ValidatedItem item, ProposalRun proposal, UUID applicationId, Timestamp now) {
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

  private void createEventDetails(UUID itemId, ValidatedEventSchedule schedule) {
    Timestamp startAt =
        schedule.startInstant() == null ? null : Timestamp.from(schedule.startInstant());
    Timestamp endAt = schedule.endInstant() == null ? null : Timestamp.from(schedule.endInstant());
    Date startDate =
        schedule.startLocalDate() == null ? null : Date.valueOf(schedule.startLocalDate());
    Date endDate =
        schedule.endLocalDateExclusive() == null
            ? null
            : Date.valueOf(schedule.endLocalDateExclusive());
    db.sql(
            """
            insert into event_details(
              memo_item_id,
              owner_id,
              item_kind,
              schedule_kind,
              start_at_utc,
              end_at_utc,
              start_local_date,
              end_local_date_exclusive,
              source_time_zone
            ) values (
              :itemId,
              :ownerId,
              'EVENT',
              :scheduleKind,
              :startAt,
              :endAt,
              :startDate,
              :endDate,
              :timeZone
            )
            """)
        .param("itemId", itemId)
        .param("ownerId", identity.ownerId())
        .param("scheduleKind", schedule.mode())
        .param("startAt", startAt)
        .param("endAt", endAt)
        .param("startDate", startDate)
        .param("endDate", endDate)
        .param("timeZone", schedule.timeZone())
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
    calendarFeeds.cancelForApplication(identity.ownerId(), application.id(), undoneAt.toInstant());
    db.sql(
            """
            delete from memo_item_relations relation
             where relation.application_id = :applicationId
               and relation.owner_id = :ownerId
            """)
        .param("applicationId", application.id())
        .param("ownerId", identity.ownerId())
        .update();
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
            delete from event_details event
             where event.owner_id = :ownerId
               and exists (
                 select 1
                   from memo_items item
                  where item.id = event.memo_item_id
                    and item.application_id = :applicationId
                    and item.owner_id = :ownerId
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
    // A preparing analysis locks every canonical tag reference through its dispatch commit. Use a
    // separate lock statement so a cleanup that had to wait gets a fresh READ COMMITTED snapshot
    // before deciding whether the now-committed dispatch protects the tag.
    db.sql(
            """
            select t.id
              from tags t
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
               and not exists (
                 select 1
                   from memo_item_relations relation
                  where relation.target_tag_id = t.id
                    and relation.owner_id = t.owner_id
               )
             order by t.id
             for update of t
            """)
        .param("ownerId", identity.ownerId())
        .query(UUID.class)
        .list();
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
               and not exists (
                 select 1
                   from memo_item_relations relation
                  where relation.target_tag_id = t.id
                    and relation.owner_id = t.owner_id
               )
               and not exists (
                 select 1
                   from analysis_run_dispatches d
                  where d.owner_id = t.owner_id
                    and d.state in ('PREPARED', 'RUNNING')
                    and (
                      d.validated_local_proposal is null
                      or exists (
                        select 1
                          from jsonb_array_elements(
                            coalesce(
                              d.validated_local_proposal::jsonb -> 'tagCandidates',
                              '[]'::jsonb
                            )
                          ) candidate
                         where lower(candidate ->> 'existingTagId') = t.id::text
                      )
                      or exists (
                        select 1
                          from jsonb_array_elements(
                            coalesce(
                              d.validated_local_proposal::jsonb -> 'relationCandidates',
                              '[]'::jsonb
                            )
                          ) relation
                         where relation ->> 'targetType' = 'TAG'
                           and lower(relation ->> 'targetId') = t.id::text
                      )
                    )
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
                   p.proposal_json::text as proposal_json
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
        || !observed.proposal().equals(locked.proposal())) {
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
        read(resultSet.getString("proposal_json")));
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

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not serialize the validated selection.", exception);
    }
  }

  private JsonNode read(String value) {
    try {
      return json.readTree(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not read the stored proposal.", exception);
    }
  }

  private record ApplyRequestV2(String hashVersion, UUID proposalId, RelationApply request) {}

  private record RelationApply(
      int expectedMemoRevision,
      String selectedType,
      String title,
      List<Tag> selectedTags,
      List<RelationApplyItem> items,
      List<SelectedRelation> selectedRelations) {}

  private record RelationApplyItem(
      String proposalCandidateId, String kind, String title, Due due) {}

  private record ApplyRequestV3(String hashVersion, UUID proposalId, EventScheduleApply request) {}

  private record EventScheduleApply(
      int expectedMemoRevision,
      String selectedType,
      String title,
      List<Tag> selectedTags,
      List<EventScheduleApplyItem> items,
      List<SelectedRelation> selectedRelations,
      String selectionSchemaVersion) {}

  private record EventScheduleApplyItem(
      String proposalCandidateId,
      String kind,
      String title,
      Due due,
      EventSchedule eventSchedule) {}

  private record LegacyApplyRequest(UUID proposalId, LegacyApply request) {}

  private record LegacyApply(
      int expectedMemoRevision,
      String selectedType,
      String title,
      List<Tag> selectedTags,
      List<LegacyItem> items) {}

  private record LegacyItem(String kind, String title, Due due) {}

  private record UndoRequest(UUID applicationId) {}

  private record ProposalRun(
      UUID runId, UUID memoId, int memoRevision, String status, JsonNode proposal) {}

  private record ApplicationRecord(
      UUID id, String status, UUID memoId, int memoRevision, UUID runId) {}

  private record PlannedItem(UUID id, ValidatedItem item) {}

  private record ProposalItemReference(int proposalItemIndex) {}

  private record RelationTarget(String targetType, UUID targetId) {}

  private record RelationIdentity(
      String sourceCandidateId, String targetType, UUID targetId, String relationType) {}

  private record ResolvedRelation(
      int proposalIndex,
      String sourceProposalCandidateId,
      int sourceProposalItemIndex,
      UUID sourceMemoItemId,
      String targetType,
      UUID targetId,
      String relationType) {}

  private record StoredSelection(
      int expectedMemoRevision,
      String selectedType,
      String title,
      List<ValidatedTag> selectedTags,
      List<StoredItem> items,
      List<StoredRelation> selectedRelations,
      @JsonInclude(JsonInclude.Include.NON_NULL) String selectionSchemaVersion) {}

  private record StoredItem(
      String proposalCandidateId,
      UUID memoItemId,
      String kind,
      String title,
      ValidatedDue due,
      @JsonInclude(JsonInclude.Include.NON_NULL) ValidatedEventSchedule eventSchedule) {}

  private record StoredRelation(
      int proposalIndex,
      String sourceProposalCandidateId,
      int sourceProposalItemIndex,
      UUID sourceMemoItemId,
      String targetType,
      UUID targetId,
      String relationType) {}
}
