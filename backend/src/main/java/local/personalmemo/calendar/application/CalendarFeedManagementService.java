package local.personalmemo.calendar.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.calendar.api.CalendarFeedDtos.AddEvent;
import local.personalmemo.calendar.api.CalendarFeedDtos.Create;
import local.personalmemo.calendar.api.CalendarFeedDtos.EligibleEvents;
import local.personalmemo.calendar.api.CalendarFeedDtos.EnableExternalPublication;
import local.personalmemo.calendar.api.CalendarFeedDtos.Entry;
import local.personalmemo.calendar.api.CalendarFeedDtos.FeedDetail;
import local.personalmemo.calendar.api.CalendarFeedDtos.FeedSummary;
import local.personalmemo.calendar.api.CalendarFeedDtos.Rotate;
import local.personalmemo.calendar.api.CalendarFeedDtos.Update;
import local.personalmemo.calendar.api.CalendarFeedDtos.Versioned;
import local.personalmemo.calendar.domain.CalendarFeedOpaqueIdGenerator;
import local.personalmemo.calendar.domain.CalendarFeedSecret;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.idempotency.IdempotencyService;
import local.personalmemo.common.security.Hashing;
import local.personalmemo.event.api.EventDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarFeedManagementService {
  private static final int MAX_FEEDS = 100;
  private static final int MAX_ENTRIES = 100;
  private static final int EVENT_PROBE_LIMIT = MAX_ENTRIES + 1;
  private static final String CREATE_OPERATION = "CALENDAR_FEED_CREATE";
  private static final String UPDATE_OPERATION = "CALENDAR_FEED_UPDATE";
  private static final String ROTATE_OPERATION = "CALENDAR_FEED_ROTATE";
  private static final String ENABLE_EXTERNAL_PUBLICATION_OPERATION =
      "CALENDAR_FEED_EXTERNAL_PUBLICATION_ENABLE";
  private static final String REVOKE_OPERATION = "CALENDAR_FEED_REVOKE";
  private static final String ADD_EVENT_OPERATION = "CALENDAR_FEED_EVENT_ADD";
  private static final String REMOVE_EVENT_OPERATION = "CALENDAR_FEED_EVENT_REMOVE";

  private final JdbcClient db;
  private final CurrentIdentity identity;
  private final IdempotencyService idempotency;
  private final CalendarFeedOpaqueIdGenerator opaqueIds;
  private final CalendarFeedPublicationProperties publication;

  public CalendarFeedManagementService(
      JdbcClient db,
      CurrentIdentity identity,
      IdempotencyService idempotency,
      CalendarFeedOpaqueIdGenerator opaqueIds,
      CalendarFeedPublicationProperties publication) {
    this.db = db;
    this.identity = identity;
    this.idempotency = idempotency;
    this.opaqueIds = opaqueIds;
    this.publication = publication;
  }

  @Transactional(readOnly = true)
  public EligibleEvents eligibleEvents() {
    List<CanonicalEvent> probed = findEligibleEvents(identity.ownerId(), EVENT_PROBE_LIMIT);
    boolean truncated = probed.size() > MAX_ENTRIES;
    List<EventDtos.View> items =
        probed.stream().limit(MAX_ENTRIES).map(CanonicalEvent::toView).toList();
    return new EligibleEvents(items, truncated);
  }

  @Transactional(readOnly = true)
  public List<FeedSummary> list() {
    return db.sql(
            """
            select feed.id,
                   feed.display_name,
                   feed.disclosure_mode,
                   feed.status,
                   feed.publication_scope,
                   feed.public_consent_policy_version,
                   feed.public_consent_granted_at,
                   feed.version,
                   feed.created_at,
                   feed.updated_at,
                   feed.rotated_at,
                   feed.revoked_at,
                   count(entry.id) filter (
                     where entry.state = 'ACTIVE'
                       and event.memo_item_id is not null
                       and item.archived_at is null
                       and memo.status = 'ACTIVE'
                       and item.memo_revision = memo.current_revision
                       and application.status = 'APPLIED'
                   ) as event_count
              from calendar_feeds feed
              left join calendar_feed_entries entry
                on entry.feed_id = feed.id
               and entry.owner_id = feed.owner_id
              left join memo_items item
                on item.id = entry.active_memo_item_id
               and item.owner_id = entry.active_owner_id
               and item.kind = entry.active_item_kind
              left join event_details event
                on event.memo_item_id = item.id
               and event.owner_id = item.owner_id
               and event.item_kind = item.kind
              left join memos memo
                on memo.id = item.memo_id
               and memo.owner_id = item.owner_id
              left join analysis_applications application
                on application.id = item.application_id
               and application.owner_id = item.owner_id
               and application.memo_id = item.memo_id
               and application.memo_revision = item.memo_revision
             where feed.owner_id = :ownerId
             group by feed.id
             order by feed.updated_at desc, feed.id
            """)
        .param("ownerId", identity.ownerId())
        .query(this::mapSummary)
        .list();
  }

  @Transactional(readOnly = true)
  public FeedDetail detail(UUID feedId) {
    FeedRecord feed = findFeed(feedId, false);
    return toDetail(feed);
  }

  @Transactional
  public FeedDetail create(String key, Create request) {
    CreateMaterial material =
        new CreateMaterial(
            normalizeDisplayName(request.displayName()),
            requireDisclosureMode(request.disclosureMode()),
            List.copyOf(request.eventIds()),
            request.bearerSecret());
    String requestHash = idempotency.hashRequest(material);
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(CREATE_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), FeedDetail.class);
    }
    acquireOwnerApplicationLock();
    requireDistinctEvents(material.eventIds());
    String verifier = CalendarFeedSecret.requireVerifier(material.bearerSecret());
    List<CanonicalEvent> events =
        lockEligibleEventsForUpdate(identity.ownerId(), material.eventIds());
    requireFeedCapacity();
    Timestamp now = Timestamp.from(Instant.now());
    UUID feedId = UUID.randomUUID();
    try {
      db.sql(
              """
              insert into calendar_feeds(
                id, owner_id, display_name, disclosure_mode, status, version, token_verifier,
                publication_scope, public_consent_policy_version, public_consent_granted_at,
                created_at, updated_at, rotated_at, revoked_at
              ) values (
                :id, :ownerId, :displayName, :disclosureMode, 'ACTIVE', 1, :verifier,
                'LOCAL_ONLY', null, null, :now, :now, :now, null
              )
              """)
          .param("id", feedId)
          .param("ownerId", identity.ownerId())
          .param("displayName", material.displayName())
          .param("disclosureMode", material.disclosureMode())
          .param("verifier", verifier)
          .param("now", now)
          .update();
      for (CanonicalEvent event : events) {
        insertEntry(feedId, event, now);
      }
    } catch (DuplicateKeyException exception) {
      throw DomainException.conflict(
          "CALENDAR_FEED_CONFLICT", "The calendar feed conflicts with an existing feed.");
    }
    FeedDetail response = toDetail(findFeed(feedId, false));
    idempotency.store(CREATE_OPERATION, key, requestHash, feedId, response);
    return response;
  }

  @Transactional
  public FeedDetail update(UUID feedId, String key, Update request) {
    UpdateMaterial material =
        new UpdateMaterial(
            feedId,
            normalizeDisplayName(request.displayName()),
            requireDisclosureMode(request.disclosureMode()),
            request.expectedVersion());
    String requestHash = idempotency.hashRequest(material);
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(UPDATE_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), FeedDetail.class);
    }
    FeedRecord feed = findFeed(feedId, true);
    requireActive(feed);
    requireVersion(feed, material.expectedVersion());
    boolean displayChanged = !feed.displayName().equals(material.displayName());
    boolean disclosureChanged = !feed.disclosureMode().equals(material.disclosureMode());
    if (disclosureChanged && "PUBLIC_HTTPS".equals(feed.publicationScope())) {
      throw DomainException.conflict(
          "CALENDAR_FEED_PUBLIC_DISCLOSURE_RECONSENT_REQUIRED",
          "A public feed cannot change its disclosure mode without a new external-publication consent.");
    }
    if (displayChanged || disclosureChanged) {
      Timestamp now = Timestamp.from(Instant.now());
      db.sql(
              """
              update calendar_feeds
                 set display_name = :displayName,
                     disclosure_mode = :disclosureMode,
                     version = version + 1,
                     updated_at = :now
               where id = :feedId
                 and owner_id = :ownerId
              """)
          .param("displayName", material.displayName())
          .param("disclosureMode", material.disclosureMode())
          .param("now", now)
          .param("feedId", feedId)
          .param("ownerId", identity.ownerId())
          .update();
      if (disclosureChanged) {
        db.sql(
                """
                update calendar_feed_entries
                   set sequence = sequence + 1,
                       updated_at = :now
                 where feed_id = :feedId
                   and owner_id = :ownerId
                   and state = 'ACTIVE'
                """)
            .param("now", now)
            .param("feedId", feedId)
            .param("ownerId", identity.ownerId())
            .update();
      }
    }
    FeedDetail response = toDetail(findFeed(feedId, false));
    idempotency.store(UPDATE_OPERATION, key, requestHash, feedId, response);
    return response;
  }

  @Transactional
  public FeedDetail rotate(UUID feedId, String key, Rotate request) {
    RotateMaterial material =
        new RotateMaterial(feedId, request.expectedVersion(), request.bearerSecret());
    String requestHash = idempotency.hashRequest(material);
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(ROTATE_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), FeedDetail.class);
    }
    FeedRecord feed = findFeed(feedId, true);
    requireActive(feed);
    requireVersion(feed, material.expectedVersion());
    requireRotationScope(feed);
    String verifier = CalendarFeedSecret.requireVerifier(material.bearerSecret());
    Timestamp now = Timestamp.from(Instant.now());
    try {
      int updated =
          db.sql(
                  """
                  update calendar_feeds
                     set token_verifier = :verifier,
                         version = version + 1,
                         updated_at = :now,
                         rotated_at = :now
                   where id = :feedId
                     and owner_id = :ownerId
                     and token_verifier <> :verifier
                  """)
              .param("verifier", verifier)
              .param("now", now)
              .param("feedId", feedId)
              .param("ownerId", identity.ownerId())
              .update();
      if (updated != 1) {
        throw DomainException.conflict(
            "CALENDAR_FEED_SECRET_UNCHANGED", "The replacement feed secret must be new.");
      }
    } catch (DuplicateKeyException exception) {
      throw DomainException.conflict(
          "CALENDAR_FEED_SECRET_IN_USE", "The replacement feed secret is already in use.");
    }
    FeedDetail response = toDetail(findFeed(feedId, false));
    idempotency.store(ROTATE_OPERATION, key, requestHash, feedId, response);
    return response;
  }

  @Transactional
  public FeedDetail enableExternalPublication(
      UUID feedId, String key, EnableExternalPublication request) {
    EnableExternalPublicationMaterial material =
        new EnableExternalPublicationMaterial(
            feedId,
            request.expectedVersion(),
            request.bearerSecret(),
            request.consentPolicyVersion());
    String requestHash = idempotency.hashRequest(material);
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(ENABLE_EXTERNAL_PUBLICATION_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), FeedDetail.class);
    }
    if (!publication.enabled()) {
      throw DomainException.conflict(
          "CALENDAR_FEED_PUBLICATION_UNAVAILABLE",
          "External calendar publication is not enabled by this deployment.");
    }
    if (!publication.consentPolicyVersion().equals(material.consentPolicyVersion())) {
      throw DomainException.conflict(
          "CALENDAR_FEED_PUBLIC_CONSENT_POLICY_MISMATCH",
          "The external-publication consent policy changed before this request.");
    }
    FeedRecord feed = findFeed(feedId, true);
    requireActive(feed);
    requireVersion(feed, material.expectedVersion());
    String verifier = CalendarFeedSecret.requireVerifier(material.bearerSecret());
    Timestamp now = Timestamp.from(Instant.now());
    try {
      int updated =
          db.sql(
                  """
                  update calendar_feeds
                     set token_verifier = :verifier,
                         publication_scope = 'PUBLIC_HTTPS',
                         public_consent_policy_version = :consentPolicyVersion,
                         public_consent_granted_at = :now,
                         version = version + 1,
                         updated_at = :now,
                         rotated_at = :now
                   where id = :feedId
                     and owner_id = :ownerId
                     and token_verifier <> :verifier
                  """)
              .param("verifier", verifier)
              .param("consentPolicyVersion", publication.consentPolicyVersion())
              .param("now", now)
              .param("feedId", feedId)
              .param("ownerId", identity.ownerId())
              .update();
      if (updated != 1) {
        throw DomainException.conflict(
            "CALENDAR_FEED_SECRET_UNCHANGED", "External publication requires a fresh feed secret.");
      }
    } catch (DuplicateKeyException exception) {
      throw DomainException.conflict(
          "CALENDAR_FEED_SECRET_IN_USE", "The replacement feed secret is already in use.");
    }
    FeedDetail response = toDetail(findFeed(feedId, false));
    idempotency.store(ENABLE_EXTERNAL_PUBLICATION_OPERATION, key, requestHash, feedId, response);
    return response;
  }

  @Transactional
  public FeedDetail revoke(UUID feedId, String key, Versioned request) {
    VersionMaterial material = new VersionMaterial(feedId, request.expectedVersion());
    String requestHash = idempotency.hashRequest(material);
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(REVOKE_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), FeedDetail.class);
    }
    FeedRecord feed = findFeed(feedId, true);
    requireVersion(feed, material.expectedVersion());
    if ("ACTIVE".equals(feed.status())) {
      Timestamp now = Timestamp.from(Instant.now());
      db.sql(
              """
              update calendar_feeds
                 set status = 'REVOKED',
                     publication_scope = 'LOCAL_ONLY',
                     public_consent_policy_version = null,
                     public_consent_granted_at = null,
                     version = version + 1,
                     updated_at = :now,
                     revoked_at = :now
               where id = :feedId
                 and owner_id = :ownerId
              """)
          .param("now", now)
          .param("feedId", feedId)
          .param("ownerId", identity.ownerId())
          .update();
    }
    FeedDetail response = toDetail(findFeed(feedId, false));
    idempotency.store(REVOKE_OPERATION, key, requestHash, feedId, response);
    return response;
  }

  @Transactional
  public FeedDetail addEvent(UUID feedId, String key, AddEvent request) {
    AddMaterial material = new AddMaterial(feedId, request.eventId(), request.expectedVersion());
    String requestHash = idempotency.hashRequest(material);
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(ADD_EVENT_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), FeedDetail.class);
    }
    acquireOwnerApplicationLock();
    CanonicalEvent event =
        lockEligibleEventsForUpdate(identity.ownerId(), List.of(material.eventId())).getFirst();
    FeedRecord feed = findFeed(feedId, true);
    requireActive(feed);
    requireVersion(feed, material.expectedVersion());
    String sourceHash = sourceHash(feedId, material.eventId());
    Optional<EntryState> existing = findEntryForUpdate(feedId, sourceHash);
    boolean changed = false;
    Timestamp now = Timestamp.from(Instant.now());
    if (existing.isPresent()) {
      if ("CANCELLED".equals(existing.get().state())) {
        reactivateEntry(existing.get().id(), event, now);
        changed = true;
      }
    } else {
      requireEntryCapacity(feedId);
      insertEntry(feedId, event, now);
      changed = true;
    }
    if (changed) {
      bumpFeed(feedId, now);
    }
    FeedDetail response = toDetail(findFeed(feedId, false));
    idempotency.store(ADD_EVENT_OPERATION, key, requestHash, feedId, response);
    return response;
  }

  @Transactional
  public FeedDetail removeEvent(UUID feedId, UUID entryId, String key, Versioned request) {
    RemoveMaterial material = new RemoveMaterial(feedId, entryId, request.expectedVersion());
    String requestHash = idempotency.hashRequest(material);
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(REMOVE_EVENT_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), FeedDetail.class);
    }
    FeedRecord feed = findFeed(feedId, true);
    requireActive(feed);
    requireVersion(feed, material.expectedVersion());
    EntryState entry = findEntryByIdForUpdate(feedId, entryId);
    if ("ACTIVE".equals(entry.state())) {
      Timestamp now = Timestamp.from(Instant.now());
      cancelEntry(entryId, now);
      bumpFeed(feedId, now);
    }
    FeedDetail response = toDetail(findFeed(feedId, false));
    idempotency.store(REMOVE_EVENT_OPERATION, key, requestHash, feedId, response);
    return response;
  }

  private List<CanonicalEvent> findEligibleEvents(UUID ownerId, int limit) {
    return db.sql(
            """
            select selected.id,
                   selected.title,
                   selected.schedule_kind,
                   selected.start_at_utc,
                   selected.end_at_utc,
                   selected.start_local_date,
                   selected.end_local_date_exclusive,
                   selected.source_time_zone
              from (
                select item.id,
                       item.title,
                       event.schedule_kind,
                       event.start_at_utc,
                       event.end_at_utc,
                       event.start_local_date,
                       event.end_local_date_exclusive,
                       event.source_time_zone,
                       application.applied_at
                  from event_details event
                  join memo_items item
                    on item.id = event.memo_item_id
                   and item.owner_id = event.owner_id
                   and item.kind = event.item_kind
                  join memos memo
                    on memo.id = item.memo_id
                   and memo.owner_id = item.owner_id
                  join analysis_applications application
                    on application.id = item.application_id
                   and application.owner_id = item.owner_id
                   and application.memo_id = item.memo_id
                   and application.memo_revision = item.memo_revision
                 where event.owner_id = :ownerId
                   and event.item_kind = 'EVENT'
                   and item.archived_at is null
                   and memo.status = 'ACTIVE'
                   and item.memo_revision = memo.current_revision
                   and application.status = 'APPLIED'
                 order by application.applied_at desc, item.id
                 limit :limit
              ) selected
             order by coalesce(
                        selected.start_at_utc,
                        selected.start_local_date::timestamp at time zone selected.source_time_zone
                      ),
                      selected.id
            """)
        .param("ownerId", ownerId)
        .param("limit", limit)
        .query(this::mapCanonicalEvent)
        .list();
  }

  private List<CanonicalEvent> lockEligibleEventsForUpdate(UUID ownerId, List<UUID> eventIds) {
    List<EventLockTarget> targets =
        eventIds.stream().sorted().map(eventId -> findEventLockTarget(ownerId, eventId)).toList();
    targets.stream()
        .map(EventLockTarget::applicationId)
        .distinct()
        .sorted()
        .forEach(applicationId -> lockApplication(ownerId, applicationId));
    targets.stream()
        .map(EventLockTarget::memoId)
        .distinct()
        .sorted()
        .forEach(memoId -> lockMemo(ownerId, memoId));
    // Undo deletes event/item rows only while holding the application lock. Memo edits and trash
    // hold the memo lock. Rechecking after both lock classes therefore closes the source race.
    return eventIds.stream().sorted().map(eventId -> findEligibleEvent(ownerId, eventId)).toList();
  }

  private EventLockTarget findEventLockTarget(UUID ownerId, UUID eventId) {
    return db.sql(
            """
            select item.application_id, item.memo_id
              from event_details event
              join memo_items item
                on item.id = event.memo_item_id
               and item.owner_id = event.owner_id
               and item.kind = event.item_kind
             where item.id = :eventId
               and event.owner_id = :ownerId
               and event.item_kind = 'EVENT'
            """)
        .param("eventId", eventId)
        .param("ownerId", ownerId)
        .query(
            (resultSet, rowNumber) ->
                new EventLockTarget(
                    resultSet.getObject("application_id", UUID.class),
                    resultSet.getObject("memo_id", UUID.class)))
        .optional()
        .orElseThrow(this::eventNotEligible);
  }

  private void lockApplication(UUID ownerId, UUID applicationId) {
    db.sql(
            """
            select id
              from analysis_applications
             where id = :applicationId
               and owner_id = :ownerId
             for update
            """)
        .param("applicationId", applicationId)
        .param("ownerId", ownerId)
        .query(UUID.class)
        .optional()
        .orElseThrow(this::eventNotEligible);
  }

  private void lockMemo(UUID ownerId, UUID memoId) {
    db.sql(
            """
            select id
              from memos
             where id = :memoId
               and owner_id = :ownerId
             for update
            """)
        .param("memoId", memoId)
        .param("ownerId", ownerId)
        .query(UUID.class)
        .optional()
        .orElseThrow(this::eventNotEligible);
  }

  private CanonicalEvent findEligibleEvent(UUID ownerId, UUID eventId) {
    return db.sql(
            """
            select item.id,
                   item.title,
                   event.schedule_kind,
                   event.start_at_utc,
                   event.end_at_utc,
                   event.start_local_date,
                   event.end_local_date_exclusive,
                   event.source_time_zone
              from event_details event
              join memo_items item
                on item.id = event.memo_item_id
               and item.owner_id = event.owner_id
               and item.kind = event.item_kind
              join memos memo
                on memo.id = item.memo_id
               and memo.owner_id = item.owner_id
              join analysis_applications application
                on application.id = item.application_id
               and application.owner_id = item.owner_id
               and application.memo_id = item.memo_id
               and application.memo_revision = item.memo_revision
             where item.id = :eventId
               and event.owner_id = :ownerId
               and event.item_kind = 'EVENT'
               and item.archived_at is null
               and memo.status = 'ACTIVE'
               and item.memo_revision = memo.current_revision
               and application.status = 'APPLIED'
            """)
        .param("eventId", eventId)
        .param("ownerId", ownerId)
        .query(this::mapCanonicalEvent)
        .optional()
        .orElseThrow(this::eventNotEligible);
  }

  private DomainException eventNotEligible() {
    return DomainException.invalid(
        "CALENDAR_FEED_EVENT_NOT_ELIGIBLE", "The event is not eligible for calendar sharing.");
  }

  private void insertEntry(UUID feedId, CanonicalEvent event, Timestamp now) {
    db.sql(
            """
            insert into calendar_feed_entries(
              id, feed_id, owner_id, source_event_hash, public_uid,
              active_memo_item_id, active_owner_id, active_item_kind, state, sequence,
              schedule_kind, start_at_utc, end_at_utc, start_local_date,
              end_local_date_exclusive, source_time_zone, created_at, updated_at, cancelled_at
            ) values (
              :id, :feedId, :ownerId, :sourceHash, :publicUid,
              :eventId, :ownerId, 'EVENT', 'ACTIVE', 0,
              :scheduleKind, :startAt, :endAt, :startDate,
              :endDate, :sourceTimeZone, :now, :now, null
            )
            """)
        .param("id", UUID.randomUUID())
        .param("feedId", feedId)
        .param("ownerId", identity.ownerId())
        .param("sourceHash", sourceHash(feedId, event.id()))
        .param("publicUid", opaqueIds.publicUid())
        .param("eventId", event.id())
        .param("scheduleKind", event.scheduleKind())
        .param("startAt", timestamp(event.startAt()))
        .param("endAt", timestamp(event.endAt()))
        .param("startDate", sqlDate(event.startDate()))
        .param("endDate", sqlDate(event.endDateExclusive()))
        .param("sourceTimeZone", event.sourceTimeZone())
        .param("now", now)
        .update();
  }

  private void reactivateEntry(UUID entryId, CanonicalEvent event, Timestamp now) {
    db.sql(
            """
            update calendar_feed_entries
               set active_memo_item_id = :eventId,
                   active_owner_id = :ownerId,
                   active_item_kind = 'EVENT',
                   state = 'ACTIVE',
                   sequence = sequence + 1,
                   schedule_kind = :scheduleKind,
                   start_at_utc = :startAt,
                   end_at_utc = :endAt,
                   start_local_date = :startDate,
                   end_local_date_exclusive = :endDate,
                   source_time_zone = :sourceTimeZone,
                   updated_at = :now,
                   cancelled_at = null
             where id = :entryId
               and owner_id = :ownerId
               and state = 'CANCELLED'
            """)
        .param("eventId", event.id())
        .param("ownerId", identity.ownerId())
        .param("scheduleKind", event.scheduleKind())
        .param("startAt", timestamp(event.startAt()))
        .param("endAt", timestamp(event.endAt()))
        .param("startDate", sqlDate(event.startDate()))
        .param("endDate", sqlDate(event.endDateExclusive()))
        .param("sourceTimeZone", event.sourceTimeZone())
        .param("now", now)
        .param("entryId", entryId)
        .update();
  }

  private void cancelEntry(UUID entryId, Timestamp now) {
    db.sql(
            """
            update calendar_feed_entries
               set active_memo_item_id = null,
                   active_owner_id = null,
                   active_item_kind = null,
                   state = 'CANCELLED',
                   sequence = sequence + 1,
                   updated_at = :now,
                   cancelled_at = :now
             where id = :entryId
               and owner_id = :ownerId
               and state = 'ACTIVE'
            """)
        .param("now", now)
        .param("entryId", entryId)
        .param("ownerId", identity.ownerId())
        .update();
  }

  private FeedRecord findFeed(UUID feedId, boolean forUpdate) {
    String lock = forUpdate ? " for update" : "";
    return db.sql(
            """
            select id, display_name, disclosure_mode, status,
                   publication_scope, public_consent_policy_version, public_consent_granted_at,
                   version,
                   created_at, updated_at, rotated_at, revoked_at
              from calendar_feeds
             where id = :feedId
               and owner_id = :ownerId
            """
                + lock)
        .param("feedId", feedId)
        .param("ownerId", identity.ownerId())
        .query(this::mapFeed)
        .optional()
        .orElseThrow(() -> DomainException.notFound("Calendar feed"));
  }

  private FeedDetail toDetail(FeedRecord feed) {
    List<Entry> entries =
        db.sql(
                """
                select entry.id,
                       case when entry.state = 'ACTIVE'
                              and memo.status = 'ACTIVE'
                              and item.archived_at is null
                              and item.memo_revision = memo.current_revision
                              and application.status = 'APPLIED'
                            then item.id else null end as event_id,
                       case when entry.state = 'ACTIVE'
                              and memo.status = 'ACTIVE'
                              and item.archived_at is null
                              and item.memo_revision = memo.current_revision
                              and application.status = 'APPLIED'
                            then item.title else null end as title,
                       entry.schedule_kind,
                       entry.start_at_utc,
                       entry.end_at_utc,
                       entry.start_local_date,
                       entry.end_local_date_exclusive,
                       entry.source_time_zone,
                       entry.state,
                       entry.sequence
                  from calendar_feed_entries entry
                  left join memo_items item
                    on item.id = entry.active_memo_item_id
                   and item.owner_id = entry.active_owner_id
                   and item.kind = entry.active_item_kind
                  left join memos memo
                    on memo.id = item.memo_id
                   and memo.owner_id = item.owner_id
                  left join analysis_applications application
                    on application.id = item.application_id
                   and application.owner_id = item.owner_id
                   and application.memo_id = item.memo_id
                   and application.memo_revision = item.memo_revision
                 where entry.feed_id = :feedId
                   and entry.owner_id = :ownerId
                 order by entry.created_at, entry.id
                """)
            .param("feedId", feed.id())
            .param("ownerId", identity.ownerId())
            .query(this::mapEntry)
            .list();
    if (entries.stream()
        .anyMatch(entry -> "ACTIVE".equals(entry.state()) && entry.eventId() == null)) {
      throw DomainException.conflict(
          "CALENDAR_FEED_PROJECTION_INTEGRITY_CONFLICT",
          "The calendar feed projection no longer matches its source event.");
    }
    int activeCount = (int) entries.stream().filter(entry -> entry.eventId() != null).count();
    return new FeedDetail(
        feed.id(),
        feed.displayName(),
        feed.disclosureMode(),
        feed.status(),
        feed.publicationScope(),
        feed.publicConsentPolicyVersion(),
        feed.publicConsentGrantedAt(),
        feed.version(),
        activeCount,
        feed.createdAt(),
        feed.updatedAt(),
        feed.rotatedAt(),
        feed.revokedAt(),
        entries);
  }

  private Optional<EntryState> findEntryForUpdate(UUID feedId, String sourceHash) {
    return db.sql(
            """
            select id, state
              from calendar_feed_entries
             where feed_id = :feedId
               and owner_id = :ownerId
               and source_event_hash = :sourceHash
             for update
            """)
        .param("feedId", feedId)
        .param("ownerId", identity.ownerId())
        .param("sourceHash", sourceHash)
        .query(
            (resultSet, rowNumber) ->
                new EntryState(resultSet.getObject("id", UUID.class), resultSet.getString("state")))
        .optional();
  }

  private EntryState findEntryByIdForUpdate(UUID feedId, UUID entryId) {
    return db.sql(
            """
            select id, state
              from calendar_feed_entries
             where id = :entryId
               and feed_id = :feedId
               and owner_id = :ownerId
             for update
            """)
        .param("entryId", entryId)
        .param("feedId", feedId)
        .param("ownerId", identity.ownerId())
        .query(
            (resultSet, rowNumber) ->
                new EntryState(resultSet.getObject("id", UUID.class), resultSet.getString("state")))
        .optional()
        .orElseThrow(() -> DomainException.notFound("Calendar feed entry"));
  }

  private void requireEntryCapacity(UUID feedId) {
    long count =
        db.sql(
                """
                select count(*)
                  from calendar_feed_entries
                 where feed_id = :feedId
                   and owner_id = :ownerId
                """)
            .param("feedId", feedId)
            .param("ownerId", identity.ownerId())
            .query(Long.class)
            .single();
    if (count >= MAX_ENTRIES) {
      throw DomainException.invalid(
          "CALENDAR_FEED_ENTRY_LIMIT_EXCEEDED",
          "A calendar feed can retain at most 100 event entries.");
    }
  }

  private void requireFeedCapacity() {
    db.sql(
            """
            select id
              from users
             where id = :ownerId
             for update
            """)
        .param("ownerId", identity.ownerId())
        .query(UUID.class)
        .single();
    long count =
        db.sql(
                """
                select count(*)
                  from calendar_feeds
                 where owner_id = :ownerId
                """)
            .param("ownerId", identity.ownerId())
            .query(Long.class)
            .single();
    if (count >= MAX_FEEDS) {
      throw DomainException.invalid(
          "CALENDAR_FEED_LIMIT_EXCEEDED", "An owner can retain at most 100 calendar feeds.");
    }
  }

  private void bumpFeed(UUID feedId, Timestamp now) {
    db.sql(
            """
            update calendar_feeds
               set version = version + 1,
                   updated_at = :now
             where id = :feedId
               and owner_id = :ownerId
            """)
        .param("now", now)
        .param("feedId", feedId)
        .param("ownerId", identity.ownerId())
        .update();
  }

  private void requireDistinctEvents(List<UUID> eventIds) {
    Set<UUID> distinct = new HashSet<>(eventIds);
    if (distinct.size() != eventIds.size()) {
      throw DomainException.invalid(
          "DUPLICATE_CALENDAR_FEED_EVENT", "Each event may be added to a feed only once.");
    }
  }

  private void acquireOwnerApplicationLock() {
    // Apply, undo, feed creation, and feed membership changes share this exact owner scope before
    // taking application, memo, feed, and entry locks in that order.
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

  private String normalizeDisplayName(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty() || normalized.length() > 80) {
      throw DomainException.invalid(
          "INVALID_CALENDAR_FEED_DISPLAY_NAME",
          "The calendar feed display name must contain between 1 and 80 characters.");
    }
    normalized
        .chars()
        .filter(character -> Character.isISOControl(character))
        .findAny()
        .ifPresent(
            ignored -> {
              throw DomainException.invalid(
                  "INVALID_CALENDAR_FEED_DISPLAY_NAME",
                  "The calendar feed display name contains unsupported control text.");
            });
    return normalized;
  }

  private String requireDisclosureMode(String value) {
    if (!("TITLE".equals(value) || "BUSY_ONLY".equals(value))) {
      throw DomainException.invalid(
          "INVALID_CALENDAR_FEED_DISCLOSURE_MODE",
          "The calendar feed disclosure mode must be TITLE or BUSY_ONLY.");
    }
    return value;
  }

  private void requireActive(FeedRecord feed) {
    if (!"ACTIVE".equals(feed.status())) {
      throw DomainException.conflict(
          "CALENDAR_FEED_REVOKED", "A revoked calendar feed cannot be changed.");
    }
  }

  private void requireVersion(FeedRecord feed, long expectedVersion) {
    if (feed.version() != expectedVersion) {
      throw DomainException.conflict(
          "CALENDAR_FEED_VERSION_CONFLICT", "The calendar feed changed before this request.");
    }
  }

  private void requireRotationScope(FeedRecord feed) {
    if (!publication.enabled()) {
      if (!"LOCAL_ONLY".equals(feed.publicationScope())) {
        throw DomainException.conflict(
            "CALENDAR_FEED_PUBLICATION_SCOPE_MISMATCH",
            "The feed publication scope is not available in this deployment mode.");
      }
      return;
    }
    if (!"PUBLIC_HTTPS".equals(feed.publicationScope())
        || feed.publicConsentGrantedAt() == null
        || !publication.consentPolicyVersion().equals(feed.publicConsentPolicyVersion())) {
      throw DomainException.conflict(
          "CALENDAR_FEED_PUBLIC_CONSENT_REQUIRED",
          "A current external-publication consent is required before rotating this feed.");
    }
  }

  private String sourceHash(UUID feedId, UUID eventId) {
    return Hashing.sha256("calendar-feed-source-v1\u0000" + feedId + "\u0000" + eventId);
  }

  private CanonicalEvent mapCanonicalEvent(ResultSet resultSet, int rowNumber) throws SQLException {
    Timestamp startAt = resultSet.getTimestamp("start_at_utc");
    Timestamp endAt = resultSet.getTimestamp("end_at_utc");
    java.sql.Date startDate = resultSet.getDate("start_local_date");
    java.sql.Date endDate = resultSet.getDate("end_local_date_exclusive");
    return new CanonicalEvent(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("title"),
        resultSet.getString("schedule_kind"),
        instant(startAt),
        instant(endAt),
        localDate(startDate),
        localDate(endDate),
        resultSet.getString("source_time_zone"));
  }

  private FeedSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
    return new FeedSummary(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("display_name"),
        resultSet.getString("disclosure_mode"),
        resultSet.getString("status"),
        resultSet.getString("publication_scope"),
        resultSet.getString("public_consent_policy_version"),
        instant(resultSet.getTimestamp("public_consent_granted_at")),
        resultSet.getLong("version"),
        resultSet.getInt("event_count"),
        instant(resultSet.getTimestamp("created_at")),
        instant(resultSet.getTimestamp("updated_at")),
        instant(resultSet.getTimestamp("rotated_at")),
        instant(resultSet.getTimestamp("revoked_at")));
  }

  private FeedRecord mapFeed(ResultSet resultSet, int rowNumber) throws SQLException {
    return new FeedRecord(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("display_name"),
        resultSet.getString("disclosure_mode"),
        resultSet.getString("status"),
        resultSet.getString("publication_scope"),
        resultSet.getString("public_consent_policy_version"),
        instant(resultSet.getTimestamp("public_consent_granted_at")),
        resultSet.getLong("version"),
        instant(resultSet.getTimestamp("created_at")),
        instant(resultSet.getTimestamp("updated_at")),
        instant(resultSet.getTimestamp("rotated_at")),
        instant(resultSet.getTimestamp("revoked_at")));
  }

  private Entry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Entry(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("event_id", UUID.class),
        resultSet.getString("title"),
        resultSet.getString("schedule_kind"),
        instant(resultSet.getTimestamp("start_at_utc")),
        instant(resultSet.getTimestamp("end_at_utc")),
        localDate(resultSet.getDate("start_local_date")),
        localDate(resultSet.getDate("end_local_date_exclusive")),
        resultSet.getString("source_time_zone"),
        resultSet.getString("state"),
        resultSet.getInt("sequence"));
  }

  private Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private java.sql.Date sqlDate(LocalDate value) {
    return value == null ? null : java.sql.Date.valueOf(value);
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private LocalDate localDate(java.sql.Date value) {
    return value == null ? null : value.toLocalDate();
  }

  private record CanonicalEvent(
      UUID id,
      String title,
      String scheduleKind,
      Instant startAt,
      Instant endAt,
      LocalDate startDate,
      LocalDate endDateExclusive,
      String sourceTimeZone) {
    EventDtos.View toView() {
      return new EventDtos.View(
          id, title, scheduleKind, startAt, endAt, startDate, endDateExclusive, sourceTimeZone);
    }
  }

  private record FeedRecord(
      UUID id,
      String displayName,
      String disclosureMode,
      String status,
      String publicationScope,
      String publicConsentPolicyVersion,
      Instant publicConsentGrantedAt,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Instant rotatedAt,
      Instant revokedAt) {}

  private record EntryState(UUID id, String state) {}

  private record EventLockTarget(UUID applicationId, UUID memoId) {}

  private record CreateMaterial(
      String displayName, String disclosureMode, List<UUID> eventIds, String bearerSecret) {}

  private record UpdateMaterial(
      UUID feedId, String displayName, String disclosureMode, long expectedVersion) {}

  private record RotateMaterial(UUID feedId, long expectedVersion, String bearerSecret) {}

  private record EnableExternalPublicationMaterial(
      UUID feedId, long expectedVersion, String bearerSecret, String consentPolicyVersion) {}

  private record VersionMaterial(UUID feedId, long expectedVersion) {}

  private record AddMaterial(UUID feedId, UUID eventId, long expectedVersion) {}

  private record RemoveMaterial(UUID feedId, UUID entryId, long expectedVersion) {}
}
