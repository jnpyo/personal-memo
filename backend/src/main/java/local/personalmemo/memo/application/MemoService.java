package local.personalmemo.memo.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.common.DevIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.idempotency.IdempotencyService;
import local.personalmemo.common.security.Hashing;
import local.personalmemo.memo.api.MemoDtos.Create;
import local.personalmemo.memo.api.MemoDtos.Update;
import local.personalmemo.memo.api.MemoDtos.View;
import local.personalmemo.memo.domain.MemoSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoService {
  private static final String CREATE_OPERATION = "MEMO_CREATE";
  private static final String UPDATE_OPERATION = "MEMO_UPDATE";
  private static final String TRASH_OPERATION = "MEMO_TRASH";
  private static final String RESTORE_OPERATION = "MEMO_RESTORE";
  private static final Set<String> LISTABLE_STATUSES = Set.of("ACTIVE", "TRASHED");
  private static final int MAX_LIST_LIMIT = 100;

  private final JdbcClient db;
  private final DevIdentity identity;
  private final IdempotencyService idempotency;

  public MemoService(JdbcClient db, DevIdentity identity, IdempotencyService idempotency) {
    this.db = db;
    this.identity = identity;
    this.idempotency = idempotency;
  }

  @Transactional
  public View create(String key, Create command) {
    validateTimeZone(command.timeZone());
    String requestHash = idempotency.hashRequest(command);
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(CREATE_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), View.class);
    }

    boolean memoIdExists =
        db.sql("select exists(select 1 from memos where id = :memoId)")
            .param("memoId", command.id())
            .query(Boolean.class)
            .single();
    if (memoIdExists) {
      throw DomainException.conflict(
          "MEMO_ID_CONFLICT", "The requested memo identifier is already in use.");
    }

    Timestamp now = Timestamp.from(Instant.now());
    db.sql(
            """
            insert into memos(
              id, owner_id, current_revision, status, pinned, created_at, updated_at
            ) values (
              :memoId, :ownerId, 1, 'ACTIVE', false, :now, :now
            )
            """)
        .param("memoId", command.id())
        .param("ownerId", identity.ownerId())
        .param("now", now)
        .update();
    db.sql(
            """
            insert into memo_revisions(
              memo_id, owner_id, revision, content, content_hash, created_at, created_by,
              client_recorded_at, source_time_zone
            ) values (
              :memoId, :ownerId, 1, :content, :contentHash, :now, :ownerId,
              :clientRecordedAt, :sourceTimeZone
            )
            """)
        .param("memoId", command.id())
        .param("content", command.content())
        .param("contentHash", Hashing.sha256(command.content()))
        .param("now", now)
        .param("ownerId", identity.ownerId())
        .param("clientRecordedAt", Timestamp.from(command.clientCreatedAt().toInstant()))
        .param("sourceTimeZone", command.timeZone())
        .update();

    View response = toView(findCurrent(command.id(), false));
    idempotency.store(CREATE_OPERATION, key, requestHash, command.id(), response);
    return response;
  }

  public View get(UUID id) {
    return toView(findCurrent(id, false));
  }

  @Transactional(readOnly = true)
  public List<View> list(String requestedStatus, int requestedLimit) {
    String status = validateListStatus(requestedStatus);
    int limit = Math.max(1, Math.min(requestedLimit, MAX_LIST_LIMIT));

    return db.sql(
            """
            select m.id,
                   m.current_revision,
                   r.content,
                   r.client_recorded_at,
                   r.source_time_zone,
                   m.status,
                   m.created_at,
                   coalesce((
                     select ar.status
                       from analysis_runs ar
                      where ar.memo_id = m.id
                        and ar.owner_id = m.owner_id
                        and ar.memo_revision = m.current_revision
                      order by ar.created_at desc
                      limit 1
                   ), 'NOT_STARTED') as analysis_state
              from memos m
              join memo_revisions r
                on r.memo_id = m.id
               and r.owner_id = m.owner_id
               and r.revision = m.current_revision
             where m.owner_id = :ownerId
               and m.status = :status
             order by m.updated_at desc, m.id
             limit :limit
            """)
        .param("ownerId", identity.ownerId())
        .param("status", status)
        .param("limit", limit)
        .query(this::mapSnapshot)
        .list()
        .stream()
        .map(this::toView)
        .toList();
  }

  /** Locks the memo identity row so revision-sensitive work serializes with memo updates. */
  @Transactional
  public MemoSnapshot getCurrentForUpdate(UUID id) {
    return findCurrent(id, true);
  }

  @Transactional
  public View update(UUID id, String key, Update command) {
    validateUpdateCaptureContext(command);
    String requestHash = idempotency.hashRequest(new UpdateRequest(id, command));
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(UPDATE_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), View.class);
    }

    MemoSnapshot current = findCurrent(id, true);
    if (!current.isActive()) {
      throw DomainException.conflict("MEMO_NOT_ACTIVE", "A trashed memo cannot be edited.");
    }
    if (current.currentRevision() != command.expectedRevision()) {
      throw staleRevision();
    }

    int nextRevision = current.currentRevision() + 1;
    Timestamp now = Timestamp.from(Instant.now());
    Timestamp clientRecordedAt =
        Timestamp.from(
            command.clientUpdatedAt() == null
                ? now.toInstant()
                : command.clientUpdatedAt().toInstant());
    String sourceTimeZone =
        command.timeZone() == null ? current.sourceTimeZone() : command.timeZone();
    db.sql(
            """
            insert into memo_revisions(
              memo_id, owner_id, revision, content, content_hash, created_at, created_by,
              client_recorded_at, source_time_zone
            ) values (
              :memoId, :ownerId, :revision, :content, :contentHash, :now, :ownerId,
              :clientRecordedAt, :sourceTimeZone
            )
            """)
        .param("memoId", id)
        .param("revision", nextRevision)
        .param("content", command.content())
        .param("contentHash", Hashing.sha256(command.content()))
        .param("now", now)
        .param("ownerId", identity.ownerId())
        .param("clientRecordedAt", clientRecordedAt)
        .param("sourceTimeZone", sourceTimeZone)
        .update();
    db.sql(
            """
            update memos
               set current_revision = :revision,
                   updated_at = :now,
                   version = version + 1
             where id = :memoId
               and owner_id = :ownerId
            """)
        .param("revision", nextRevision)
        .param("now", now)
        .param("memoId", id)
        .param("ownerId", identity.ownerId())
        .update();
    db.sql(
            """
            update analysis_runs
               set status = 'STALE'
             where memo_id = :memoId
               and owner_id = :ownerId
               and memo_revision < :revision
               and status not in ('APPLIED', 'REJECTED', 'STALE')
            """)
        .param("memoId", id)
        .param("ownerId", identity.ownerId())
        .param("revision", nextRevision)
        .update();

    View response = toView(findCurrent(id, false));
    idempotency.store(UPDATE_OPERATION, key, requestHash, id, response);
    return response;
  }

  @Transactional
  public View trash(UUID id, String key) {
    String requestHash = idempotency.hashRequest(new StatusChangeRequest(id));
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(TRASH_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), View.class);
    }

    MemoSnapshot current = findCurrent(id, true);
    if (current.isActive()) {
      Timestamp now = Timestamp.from(Instant.now());
      db.sql(
              """
              update memos
                 set status = 'TRASHED',
                     deleted_at = :now,
                     updated_at = :now,
                     version = version + 1
               where id = :memoId
                 and owner_id = :ownerId
              """)
          .param("now", now)
          .param("memoId", id)
          .param("ownerId", identity.ownerId())
          .update();
      db.sql(
              """
              update analysis_runs
                 set status = 'STALE'
               where memo_id = :memoId
                 and owner_id = :ownerId
                 and status in ('REVIEW_REQUIRED', 'POSTPONED')
              """)
          .param("memoId", id)
          .param("ownerId", identity.ownerId())
          .update();
    }

    View response = toView(findCurrent(id, false));
    idempotency.store(TRASH_OPERATION, key, requestHash, id, response);
    return response;
  }

  @Transactional
  public View restore(UUID id, String key) {
    String requestHash = idempotency.hashRequest(new StatusChangeRequest(id));
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(RESTORE_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), View.class);
    }

    MemoSnapshot current = findCurrent(id, true);
    if (!current.isActive()) {
      Timestamp now = Timestamp.from(Instant.now());
      db.sql(
              """
              update memos
                 set status = 'ACTIVE',
                     deleted_at = null,
                     updated_at = :now,
                     version = version + 1
               where id = :memoId
                 and owner_id = :ownerId
              """)
          .param("now", now)
          .param("memoId", id)
          .param("ownerId", identity.ownerId())
          .update();
    }

    View response = toView(findCurrent(id, false));
    idempotency.store(RESTORE_OPERATION, key, requestHash, id, response);
    return response;
  }

  private MemoSnapshot findCurrent(UUID id, boolean forUpdate) {
    String lockingClause = forUpdate ? " for update of m" : "";
    return db.sql(
            """
            select m.id,
                   m.current_revision,
                   r.content,
                   r.client_recorded_at,
                   r.source_time_zone,
                   m.status,
                   m.created_at,
                   coalesce((
                     select ar.status
                       from analysis_runs ar
                      where ar.memo_id = m.id
                        and ar.owner_id = m.owner_id
                        and ar.memo_revision = m.current_revision
                      order by ar.created_at desc
                      limit 1
                   ), 'NOT_STARTED') as analysis_state
              from memos m
              join memo_revisions r
                on r.memo_id = m.id
               and r.owner_id = m.owner_id
               and r.revision = m.current_revision
             where m.id = :memoId
               and m.owner_id = :ownerId
            """
                + lockingClause)
        .param("memoId", id)
        .param("ownerId", identity.ownerId())
        .query(this::mapSnapshot)
        .optional()
        .orElseThrow(() -> DomainException.notFound("Memo"));
  }

  private MemoSnapshot mapSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
    return new MemoSnapshot(
        resultSet.getObject("id", UUID.class),
        resultSet.getInt("current_revision"),
        resultSet.getString("content"),
        resultSet.getTimestamp("client_recorded_at").toInstant(),
        resultSet.getString("source_time_zone"),
        resultSet.getString("status"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getString("analysis_state"));
  }

  private View toView(MemoSnapshot memo) {
    return new View(
        memo.id(),
        memo.currentRevision(),
        memo.content(),
        memo.status(),
        memo.analysisState(),
        memo.createdAt());
  }

  private void validateTimeZone(String timeZone) {
    if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
      throw DomainException.invalid(
          "INVALID_TIME_ZONE", "timeZone must be a recognized IANA time-zone identifier.");
    }
  }

  private void validateUpdateCaptureContext(Update command) {
    boolean hasClientUpdatedAt = command.clientUpdatedAt() != null;
    boolean hasTimeZone = command.timeZone() != null;
    if (hasClientUpdatedAt != hasTimeZone) {
      throw DomainException.invalid(
          "INVALID_CAPTURE_CONTEXT",
          "clientUpdatedAt and timeZone must either both be provided or both be omitted.");
    }
    if (hasTimeZone) {
      validateTimeZone(command.timeZone());
    }
  }

  private String validateListStatus(String status) {
    if (!LISTABLE_STATUSES.contains(status)) {
      throw DomainException.invalid(
          "INVALID_MEMO_STATUS", "status must be ACTIVE or TRASHED.");
    }
    return status;
  }

  private DomainException staleRevision() {
    return DomainException.conflict(
        "STALE_MEMO_REVISION", "The memo changed after this revision was read.");
  }

  private record UpdateRequest(UUID memoId, Update command) {}

  private record StatusChangeRequest(UUID memoId) {}
}
