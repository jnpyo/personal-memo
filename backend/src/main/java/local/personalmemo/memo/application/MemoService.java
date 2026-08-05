package local.personalmemo.memo.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
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
              memo_id, revision, content, content_hash, created_at, created_by
            ) values (
              :memoId, 1, :content, :contentHash, :now, :ownerId
            )
            """)
        .param("memoId", command.id())
        .param("content", command.content())
        .param("contentHash", Hashing.sha256(command.content()))
        .param("now", now)
        .param("ownerId", identity.ownerId())
        .update();

    View response = toView(findCurrent(command.id(), false));
    idempotency.store(CREATE_OPERATION, key, requestHash, command.id(), response);
    return response;
  }

  public View get(UUID id) {
    return toView(findCurrent(id, false));
  }

  /** Locks the memo identity row so revision-sensitive work serializes with memo updates. */
  @Transactional
  public MemoSnapshot getCurrentForUpdate(UUID id) {
    return findCurrent(id, true);
  }

  @Transactional
  public View update(UUID id, Update command) {
    MemoSnapshot current = findCurrent(id, true);
    if (!current.isActive()) {
      throw DomainException.conflict("MEMO_NOT_ACTIVE", "A trashed memo cannot be edited.");
    }
    if (current.currentRevision() != command.expectedRevision()) {
      throw staleRevision();
    }

    int nextRevision = current.currentRevision() + 1;
    Timestamp now = Timestamp.from(Instant.now());
    db.sql(
            """
            insert into memo_revisions(
              memo_id, revision, content, content_hash, created_at, created_by
            ) values (
              :memoId, :revision, :content, :contentHash, :now, :ownerId
            )
            """)
        .param("memoId", id)
        .param("revision", nextRevision)
        .param("content", command.content())
        .param("contentHash", Hashing.sha256(command.content()))
        .param("now", now)
        .param("ownerId", identity.ownerId())
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

    return toView(findCurrent(id, false));
  }

  private MemoSnapshot findCurrent(UUID id, boolean forUpdate) {
    String lockingClause = forUpdate ? " for update of m" : "";
    return db.sql(
            """
            select m.id,
                   m.current_revision,
                   r.content,
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

  private DomainException staleRevision() {
    return DomainException.conflict(
        "STALE_MEMO_REVISION", "The memo changed after this revision was read.");
  }
}
