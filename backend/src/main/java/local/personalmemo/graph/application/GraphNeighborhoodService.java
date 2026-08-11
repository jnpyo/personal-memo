package local.personalmemo.graph.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.graph.api.GraphDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GraphNeighborhoodService {
  static final String MEMO_SORT_SHAPE = "MEMO_HOME_PRIORITY_V1";
  static final String TAG_SORT_SHAPE = "TAG_NORMALIZED_NAME_V1";

  private static final int MAX_PAGE_SIZE = 20;
  static final String DIGEST_SHAPE = "GRAPH_NEIGHBORHOOD_SNAPSHOT_V1";
  private static final Duration CURSOR_MAX_AGE = Duration.ofHours(24);
  private static final Duration CURSOR_MAX_FUTURE_SKEW = Duration.ofMinutes(1);
  private static final UUID ZERO_UUID = new UUID(0, 0);

  private static final String MEMO_CANDIDATES_CTE =
      """
      with memo_candidates as (
        select m.id,
               m.pinned,
               latest_application.selection_json ->> 'title' as title,
               latest_application.selection_json ->> 'selectedType' as kind,
               task_summary.task_status,
               coalesce(task_summary.overdue, false) as overdue,
               coalesce(task_summary.has_todo, false) as has_todo,
               task_summary.next_todo_due,
               current_revision.created_at as revision_created_at,
               case when m.pinned then 0 else 1 end as pinned_rank,
               case when coalesce(task_summary.overdue, false) then 0 else 1 end as overdue_rank,
               case when coalesce(task_summary.has_todo, false) then 0 else 1 end as todo_rank,
               case when task_summary.next_todo_due is null then 1 else 0 end as due_null_rank
          from memos m
          join memo_revisions current_revision
            on current_revision.memo_id = m.id
           and current_revision.owner_id = m.owner_id
           and current_revision.revision = m.current_revision
          join lateral (
            select a.id, a.selection_json
              from analysis_applications a
             where a.memo_id = m.id
               and a.owner_id = m.owner_id
               and a.status = 'APPLIED'
             order by a.applied_at desc, a.id desc
             limit 1
          ) latest_application on true
          left join lateral (
            select case
                     when bool_or(t.status = 'TODO') then 'TODO'
                     when bool_or(t.status = 'DONE') then 'DONE'
                     when bool_or(t.status = 'CANCELLED') then 'CANCELLED'
                     else null
                   end as task_status,
                   coalesce(
                     bool_or(
                       t.status = 'TODO'
                       and (
                         (t.due_at_utc is not null
                          and t.due_at_utc < cast(:snapshotAsOf as timestamptz))
                         or (
                           t.due_local_date is not null
                           and t.due_local_date <
                             (cast(:snapshotAsOf as timestamptz)
                              at time zone t.source_time_zone)::date
                         )
                       )
                     ),
                     false
                   ) as overdue,
                   coalesce(bool_or(t.status = 'TODO'), false) as has_todo,
                   min(
                     case
                       when t.status = 'TODO' then
                         coalesce(
                           t.due_at_utc,
                           t.due_local_date::timestamp at time zone t.source_time_zone
                         )
                       else null
                     end
                   ) as next_todo_due
              from memo_items i
              join task_details t
                on t.memo_item_id = i.id
               and t.owner_id = i.owner_id
             where i.memo_id = m.id
               and i.owner_id = m.owner_id
               and i.archived_at is null
          ) task_summary on true
         where m.owner_id = :ownerId
           and m.status = 'ACTIVE'
           and jsonb_typeof(latest_application.selection_json) = 'object'
           and jsonb_typeof(latest_application.selection_json -> 'title') = 'string'
           and btrim(latest_application.selection_json ->> 'title') <> ''
           and jsonb_typeof(latest_application.selection_json -> 'selectedType') = 'string'
           and latest_application.selection_json ->> 'selectedType'
                 in ('TASK', 'EVENT', 'INFORMATION', 'IDEA', 'RECORD')
           and exists (
             select 1
               from memo_items latest_item
              where latest_item.application_id = latest_application.id
                and latest_item.memo_id = m.id
                and latest_item.owner_id = m.owner_id
                and latest_item.archived_at is null
           )
      )
      """;

  private static final String MEMO_LINK_EXISTS =
      """
      exists (
        select 1
          from memo_items linked_item
          join item_tags linked_tag
            on linked_tag.memo_item_id = linked_item.id
           and linked_tag.owner_id = linked_item.owner_id
           and linked_tag.application_id = linked_item.application_id
          join analysis_applications link_application
            on link_application.id = linked_tag.application_id
           and link_application.owner_id = linked_tag.owner_id
           and link_application.status = 'APPLIED'
         where linked_item.memo_id = candidate.id
           and linked_item.owner_id = :ownerId
           and linked_item.archived_at is null
           and linked_tag.tag_id = :centerId
      )
      """;

  private static final String MEMO_AFTER_PREDICATE =
      """
      (
        :hasCursor = false
        or candidate.pinned_rank > :cursorPinnedRank
        or (
          candidate.pinned_rank = :cursorPinnedRank
          and candidate.overdue_rank > :cursorOverdueRank
        )
        or (
          candidate.pinned_rank = :cursorPinnedRank
          and candidate.overdue_rank = :cursorOverdueRank
          and candidate.todo_rank > :cursorTodoRank
        )
        or (
          candidate.pinned_rank = :cursorPinnedRank
          and candidate.overdue_rank = :cursorOverdueRank
          and candidate.todo_rank = :cursorTodoRank
          and candidate.due_null_rank > :cursorDueNullRank
        )
        or (
          candidate.pinned_rank = :cursorPinnedRank
          and candidate.overdue_rank = :cursorOverdueRank
          and candidate.todo_rank = :cursorTodoRank
          and candidate.due_null_rank = :cursorDueNullRank
          and (
            (
              :cursorDueNullRank = 0
              and candidate.next_todo_due > cast(:cursorNextDue as timestamptz)
            )
            or (
              (
                :cursorDueNullRank = 1
                or candidate.next_todo_due = cast(:cursorNextDue as timestamptz)
              )
              and (
                candidate.revision_created_at < cast(:cursorRevisionCreatedAt as timestamptz)
                or (
                  candidate.revision_created_at = cast(:cursorRevisionCreatedAt as timestamptz)
                  and candidate.id > cast(:cursorNeighborId as uuid)
                )
              )
            )
          )
        )
      )
      """;

  private final JdbcClient db;
  private final CurrentIdentity identity;
  private final GraphNeighborhoodCursorCodec cursors;
  private final Clock clock;

  @Autowired
  public GraphNeighborhoodService(
      JdbcClient db, CurrentIdentity identity, GraphNeighborhoodCursorCodec cursors) {
    this(db, identity, cursors, Clock.systemUTC());
  }

  GraphNeighborhoodService(
      JdbcClient db, CurrentIdentity identity, GraphNeighborhoodCursorCodec cursors, Clock clock) {
    this.db = db;
    this.identity = identity;
    this.cursors = cursors;
    this.clock = clock;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public GraphDtos.Neighborhood neighborhood(
      String requestedKind, UUID centerId, int requestedLimit, String encodedCursor) {
    NodeKind kind = parseKind(requestedKind);
    UUID ownerId = identity.ownerId();

    // Verify the center before inspecting cursor content so foreign and unavailable nodes never
    // produce cursor-dependent responses.
    requireCenter(ownerId, kind, centerId);
    int limit = validateLimit(requestedLimit);

    String sortShape = kind == NodeKind.MEMO ? TAG_SORT_SHAPE : MEMO_SORT_SHAPE;
    GraphNeighborhoodCursorCodec.DecodedCursor decoded =
        encodedCursor == null
            ? null
            : cursors.decode(encodedCursor, ownerId, kind.name(), centerId, sortShape);
    Instant now = clock.instant();
    Instant snapshotAsOf = decoded == null ? now : decoded.snapshotAsOf();
    if (decoded != null
        && (snapshotAsOf.isBefore(now.minus(CURSOR_MAX_AGE))
            || snapshotAsOf.isAfter(now.plus(CURSOR_MAX_FUTURE_SKEW)))) {
      throw invalidCursor();
    }

    return switch (kind) {
      case MEMO -> memoNeighborhood(ownerId, centerId, limit, snapshotAsOf, decoded);
      case TAG -> tagNeighborhood(ownerId, centerId, limit, snapshotAsOf, decoded);
    };
  }

  private GraphDtos.Neighborhood memoNeighborhood(
      UUID ownerId,
      UUID centerId,
      int limit,
      Instant snapshotAsOf,
      GraphNeighborhoodCursorCodec.DecodedCursor decoded) {
    MemoCandidate center = findMemoCenter(ownerId, centerId, snapshotAsOf);
    String neighborhoodDigest = null;
    if (decoded != null) {
      neighborhoodDigest = memoNeighborhoodDigest(ownerId, centerId, center, snapshotAsOf);
      requireMatchingDigest(decoded, neighborhoodDigest);
    }
    TagCandidate after =
        decoded == null
            ? null
            : findTagNeighbor(ownerId, centerId, decoded.lastNeighborId())
                .orElseThrow(this::invalidCursor);
    List<TagCandidate> candidates = findTagNeighbors(ownerId, centerId, after, limit + 1);
    boolean truncated = candidates.size() > limit;
    List<TagCandidate> selected =
        List.copyOf(candidates.subList(0, Math.min(candidates.size(), limit)));
    if (truncated && neighborhoodDigest == null) {
      neighborhoodDigest = memoNeighborhoodDigest(ownerId, centerId, center, snapshotAsOf);
    }
    String nextCursor =
        truncated
            ? cursors.encode(
                ownerId,
                NodeKind.MEMO.name(),
                centerId,
                TAG_SORT_SHAPE,
                snapshotAsOf,
                neighborhoodDigest,
                selected.getLast().id())
            : null;
    List<GraphDtos.Node> neighbors = selected.stream().map(this::tagNode).toList();
    List<GraphDtos.Edge> edges =
        selected.stream().map(tag -> memoTagEdge(centerId, tag.id())).toList();
    return new GraphDtos.Neighborhood(memoNode(center), neighbors, edges, truncated, nextCursor);
  }

  private GraphDtos.Neighborhood tagNeighborhood(
      UUID ownerId,
      UUID centerId,
      int limit,
      Instant snapshotAsOf,
      GraphNeighborhoodCursorCodec.DecodedCursor decoded) {
    TagCandidate center = findTagCenter(ownerId, centerId);
    String neighborhoodDigest = null;
    if (decoded != null) {
      neighborhoodDigest = tagNeighborhoodDigest(ownerId, centerId, center, snapshotAsOf);
      requireMatchingDigest(decoded, neighborhoodDigest);
    }
    MemoCandidate after =
        decoded == null
            ? null
            : findMemoNeighbor(ownerId, centerId, decoded.lastNeighborId(), snapshotAsOf)
                .orElseThrow(this::invalidCursor);
    List<MemoCandidate> candidates =
        findMemoNeighbors(ownerId, centerId, after, snapshotAsOf, limit + 1);
    boolean truncated = candidates.size() > limit;
    List<MemoCandidate> selected =
        List.copyOf(candidates.subList(0, Math.min(candidates.size(), limit)));
    if (truncated && neighborhoodDigest == null) {
      neighborhoodDigest = tagNeighborhoodDigest(ownerId, centerId, center, snapshotAsOf);
    }
    String nextCursor =
        truncated
            ? cursors.encode(
                ownerId,
                NodeKind.TAG.name(),
                centerId,
                MEMO_SORT_SHAPE,
                snapshotAsOf,
                neighborhoodDigest,
                selected.getLast().id())
            : null;
    List<GraphDtos.Node> neighbors = selected.stream().map(this::memoNode).toList();
    List<GraphDtos.Edge> edges =
        selected.stream().map(memo -> memoTagEdge(memo.id(), centerId)).toList();
    return new GraphDtos.Neighborhood(tagNode(center), neighbors, edges, truncated, nextCursor);
  }

  private void requireCenter(UUID ownerId, NodeKind kind, UUID centerId) {
    boolean exists =
        switch (kind) {
          case MEMO -> memoCenterExists(ownerId, centerId);
          case TAG -> tagCenterExists(ownerId, centerId);
        };
    if (!exists) {
      throw DomainException.notFound("Graph node");
    }
  }

  private boolean memoCenterExists(UUID ownerId, UUID centerId) {
    return db.sql(
            """
            select exists (
              select 1
                from memos m
                join memo_revisions current_revision
                  on current_revision.memo_id = m.id
                 and current_revision.owner_id = m.owner_id
                 and current_revision.revision = m.current_revision
                join lateral (
                  select a.id, a.selection_json
                    from analysis_applications a
                   where a.memo_id = m.id
                     and a.owner_id = m.owner_id
                     and a.status = 'APPLIED'
                   order by a.applied_at desc, a.id desc
                   limit 1
                ) latest_application on true
               where m.id = :centerId
                 and m.owner_id = :ownerId
                 and m.status = 'ACTIVE'
                 and jsonb_typeof(latest_application.selection_json) = 'object'
                 and jsonb_typeof(latest_application.selection_json -> 'title') = 'string'
                 and btrim(latest_application.selection_json ->> 'title') <> ''
                 and jsonb_typeof(latest_application.selection_json -> 'selectedType') = 'string'
                 and latest_application.selection_json ->> 'selectedType'
                       in ('TASK', 'EVENT', 'INFORMATION', 'IDEA', 'RECORD')
                 and exists (
                   select 1
                     from memo_items latest_item
                    where latest_item.application_id = latest_application.id
                      and latest_item.memo_id = m.id
                      and latest_item.owner_id = m.owner_id
                      and latest_item.archived_at is null
                 )
            )
            """)
        .param("centerId", centerId)
        .param("ownerId", ownerId)
        .query(Boolean.class)
        .single();
  }

  private boolean tagCenterExists(UUID ownerId, UUID centerId) {
    return db.sql(
            """
            select exists (
              select 1
                from tags
               where id = :centerId
                 and owner_id = :ownerId
                 and state = 'ACTIVE'
            )
            """)
        .param("centerId", centerId)
        .param("ownerId", ownerId)
        .query(Boolean.class)
        .single();
  }

  private MemoCandidate findMemoCenter(UUID ownerId, UUID centerId, Instant snapshotAsOf) {
    return db.sql(
            MEMO_CANDIDATES_CTE
                + """
                select *
                  from memo_candidates
                 where id = :centerId
                """)
        .param("ownerId", ownerId)
        .param("centerId", centerId)
        .param("snapshotAsOf", Timestamp.from(snapshotAsOf))
        .query(this::memoCandidate)
        .optional()
        .orElseThrow(() -> DomainException.notFound("Graph node"));
  }

  private TagCandidate findTagCenter(UUID ownerId, UUID centerId) {
    return db.sql(
            """
            select id, canonical_name, normalized_name
              from tags
             where id = :centerId
               and owner_id = :ownerId
               and state = 'ACTIVE'
            """)
        .param("centerId", centerId)
        .param("ownerId", ownerId)
        .query(this::tagCandidate)
        .optional()
        .orElseThrow(() -> DomainException.notFound("Graph node"));
  }

  private String memoNeighborhoodDigest(
      UUID ownerId, UUID centerId, MemoCandidate center, Instant snapshotAsOf) {
    return db.sql(memoNeighborhoodDigestSql())
        .param("digestShape", DIGEST_SHAPE)
        .param("ownerId", ownerId)
        .param("centerId", centerId)
        .param("sortShape", TAG_SORT_SHAPE)
        .param("snapshotAsOfText", snapshotAsOf.toString())
        .param("centerLabel", center.title())
        .param("centerNodeKind", center.kind())
        .param("centerTaskStatus", center.taskStatus() == null ? "NONE" : center.taskStatus())
        .param("centerOverdue", center.overdue())
        .param("centerPinned", center.pinned())
        .query(String.class)
        .single();
  }

  static String memoNeighborhoodDigestSql() {
    return """
           select encode(
                    sha256(
                      convert_to(
                        jsonb_build_array(
                          :digestShape,
                          cast(:ownerId as text),
                          'MEMO',
                          cast(:centerId as text),
                          :sortShape,
                          :snapshotAsOfText,
                          jsonb_build_array(
                            cast(:centerId as text),
                            'MEMO',
                            :centerLabel,
                            :centerNodeKind,
                            :centerTaskStatus,
                            :centerOverdue,
                            :centerPinned
                          ),
                          coalesce(
                            jsonb_agg(
                              jsonb_build_array(
                                cast(visible_tag.id as text),
                                'TAG',
                                visible_tag.canonical_name,
                                null::text,
                                null::text,
                                false,
                                false,
                                visible_tag.normalized_name
                              )
                              order by visible_tag.normalized_name, visible_tag.id
                            ),
                            '[]'::jsonb
                          )
                        )::text,
                        'UTF8'
                      )
                    ),
                    'hex'
                  ) as neighborhood_digest
             from (
               select tag.id, tag.canonical_name, tag.normalized_name
                 from tags tag
                where tag.owner_id = :ownerId
                  and tag.state = 'ACTIVE'
                  and exists (
                    select 1
                      from memo_items item
                      join item_tags link
                        on link.memo_item_id = item.id
                       and link.owner_id = item.owner_id
                       and link.application_id = item.application_id
                      join analysis_applications link_application
                        on link_application.id = link.application_id
                       and link_application.owner_id = link.owner_id
                       and link_application.status = 'APPLIED'
                     where item.memo_id = :centerId
                       and item.owner_id = :ownerId
                       and item.archived_at is null
                       and link.tag_id = tag.id
                  )
             ) visible_tag
           """;
  }

  private String tagNeighborhoodDigest(
      UUID ownerId, UUID centerId, TagCandidate center, Instant snapshotAsOf) {
    return db.sql(tagNeighborhoodDigestSql())
        .param("ownerId", ownerId)
        .param("centerId", centerId)
        .param("snapshotAsOf", Timestamp.from(snapshotAsOf))
        .param("digestShape", DIGEST_SHAPE)
        .param("sortShape", MEMO_SORT_SHAPE)
        .param("snapshotAsOfText", snapshotAsOf.toString())
        .param("centerLabel", center.canonicalName())
        .param("centerNormalizedName", center.normalizedName())
        .query(String.class)
        .single();
  }

  static String tagNeighborhoodDigestSql() {
    return MEMO_CANDIDATES_CTE
        + """
        select encode(
                 sha256(
                   convert_to(
                     jsonb_build_array(
                       :digestShape,
                       cast(:ownerId as text),
                       'TAG',
                       cast(:centerId as text),
                       :sortShape,
                       :snapshotAsOfText,
                       jsonb_build_array(
                         cast(:centerId as text),
                         'TAG',
                         :centerLabel,
                         null::text,
                         null::text,
                         false,
                         false,
                         :centerNormalizedName
                       ),
                       coalesce(
                         jsonb_agg(
                           jsonb_build_array(
                             cast(visible_memo.id as text),
                             'MEMO',
                             visible_memo.title,
                             visible_memo.kind,
                             coalesce(visible_memo.task_status, 'NONE'),
                             visible_memo.overdue,
                             visible_memo.pinned,
                             visible_memo.pinned_rank,
                             visible_memo.overdue_rank,
                             visible_memo.todo_rank,
                             visible_memo.due_null_rank,
                             case
                               when visible_memo.next_todo_due is null then null
                               else to_char(
                                 visible_memo.next_todo_due at time zone 'UTC',
                                 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
                               )
                             end,
                             to_char(
                               visible_memo.revision_created_at at time zone 'UTC',
                               'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
                             )
                           )
                           order by visible_memo.pinned_rank,
                                    visible_memo.overdue_rank,
                                    visible_memo.todo_rank,
                                    visible_memo.due_null_rank,
                                    visible_memo.next_todo_due asc nulls last,
                                    visible_memo.revision_created_at desc,
                                    visible_memo.id
                         ),
                         '[]'::jsonb
                       )
                     )::text,
                     'UTF8'
                   )
                 ),
                 'hex'
               ) as neighborhood_digest
          from (
            select candidate.*
              from memo_candidates candidate
             where
        """
        + MEMO_LINK_EXISTS
        + """
          ) visible_memo
        """;
  }

  private void requireMatchingDigest(
      GraphNeighborhoodCursorCodec.DecodedCursor decoded, String currentDigest) {
    // This digest detects projection changes; owner-scoped center verification remains the
    // authorization boundary.
    if (decoded != null && !decoded.neighborhoodDigest().equals(currentDigest)) {
      throw invalidCursor();
    }
  }

  private List<TagCandidate> findTagNeighbors(
      UUID ownerId, UUID centerId, TagCandidate after, int limit) {
    return tagNeighborQuery(ownerId, centerId, after, null, limit).list();
  }

  private java.util.Optional<TagCandidate> findTagNeighbor(
      UUID ownerId, UUID centerId, UUID neighborId) {
    return tagNeighborQuery(ownerId, centerId, null, neighborId, 1).optional();
  }

  private JdbcClient.MappedQuerySpec<TagCandidate> tagNeighborQuery(
      UUID ownerId, UUID centerId, TagCandidate after, UUID exactNeighborId, int limit) {
    JdbcClient.StatementSpec statement =
        db.sql(tagNeighborSql(exactNeighborId != null))
            .param("ownerId", ownerId)
            .param("centerId", centerId)
            .param("hasCursor", after != null)
            .param("cursorNormalizedName", after == null ? "" : after.normalizedName())
            .param("cursorNeighborId", after == null ? ZERO_UUID : after.id())
            .param("limit", limit);
    if (exactNeighborId != null) {
      statement = statement.param("exactNeighborId", exactNeighborId);
    }
    return statement.query(this::tagCandidate);
  }

  static String tagNeighborSql(boolean exactNeighbor) {
    String exactFilter = exactNeighbor ? "and tag.id = :exactNeighborId" : "";
    return """
           select tag.id, tag.canonical_name, tag.normalized_name
             from tags tag
            where tag.owner_id = :ownerId
              and tag.state = 'ACTIVE'
              and exists (
                select 1
                  from memo_items item
                  join item_tags link
                    on link.memo_item_id = item.id
                   and link.owner_id = item.owner_id
                   and link.application_id = item.application_id
                  join analysis_applications link_application
                    on link_application.id = link.application_id
                   and link_application.owner_id = link.owner_id
                   and link_application.status = 'APPLIED'
                 where item.memo_id = :centerId
                   and item.owner_id = :ownerId
                   and item.archived_at is null
                   and link.tag_id = tag.id
              )
           """
        + exactFilter
        + """
              and (
                :hasCursor = false
                or tag.normalized_name > :cursorNormalizedName
                or (
                  tag.normalized_name = :cursorNormalizedName
                  and tag.id > cast(:cursorNeighborId as uuid)
                )
              )
            order by tag.normalized_name, tag.id
            limit :limit
           """;
  }

  private List<MemoCandidate> findMemoNeighbors(
      UUID ownerId, UUID centerId, MemoCandidate after, Instant snapshotAsOf, int limit) {
    return memoNeighborQuery(ownerId, centerId, after, null, snapshotAsOf, limit).list();
  }

  private java.util.Optional<MemoCandidate> findMemoNeighbor(
      UUID ownerId, UUID centerId, UUID neighborId, Instant snapshotAsOf) {
    return memoNeighborQuery(ownerId, centerId, null, neighborId, snapshotAsOf, 1).optional();
  }

  private JdbcClient.MappedQuerySpec<MemoCandidate> memoNeighborQuery(
      UUID ownerId,
      UUID centerId,
      MemoCandidate after,
      UUID exactNeighborId,
      Instant snapshotAsOf,
      int limit) {
    JdbcClient.StatementSpec statement =
        db.sql(memoNeighborSql(exactNeighborId != null))
            .param("ownerId", ownerId)
            .param("centerId", centerId)
            .param("snapshotAsOf", Timestamp.from(snapshotAsOf))
            .param("hasCursor", after != null)
            .param("cursorPinnedRank", after == null ? 0 : after.pinnedRank())
            .param("cursorOverdueRank", after == null ? 0 : after.overdueRank())
            .param("cursorTodoRank", after == null ? 0 : after.todoRank())
            .param("cursorDueNullRank", after == null ? 0 : after.dueNullRank())
            .param(
                "cursorNextDue",
                Timestamp.from(
                    after == null || after.nextTodoDue() == null
                        ? Instant.EPOCH
                        : after.nextTodoDue()))
            .param(
                "cursorRevisionCreatedAt",
                Timestamp.from(after == null ? Instant.EPOCH : after.revisionCreatedAt()))
            .param("cursorNeighborId", after == null ? ZERO_UUID : after.id())
            .param("limit", limit);
    if (exactNeighborId != null) {
      statement = statement.param("exactNeighborId", exactNeighborId);
    }
    return statement.query(this::memoCandidate);
  }

  static String memoNeighborSql(boolean exactNeighbor) {
    String exactFilter = exactNeighbor ? "and candidate.id = :exactNeighborId" : "";
    return MEMO_CANDIDATES_CTE
        + """
        select candidate.*
          from memo_candidates candidate
         where
        """
        + MEMO_LINK_EXISTS
        + "\n"
        + exactFilter
        + "\n and "
        + MEMO_AFTER_PREDICATE
        + """
         order by candidate.pinned_rank,
                  candidate.overdue_rank,
                  candidate.todo_rank,
                  candidate.due_null_rank,
                  candidate.next_todo_due asc nulls last,
                  candidate.revision_created_at desc,
                  candidate.id
         limit :limit
        """;
  }

  private MemoCandidate memoCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
    Timestamp nextTodoDue = resultSet.getTimestamp("next_todo_due");
    return new MemoCandidate(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("title"),
        resultSet.getString("kind"),
        resultSet.getString("task_status"),
        resultSet.getBoolean("overdue"),
        resultSet.getBoolean("pinned"),
        resultSet.getBoolean("has_todo"),
        nextTodoDue == null ? null : nextTodoDue.toInstant(),
        resultSet.getTimestamp("revision_created_at").toInstant(),
        resultSet.getInt("pinned_rank"),
        resultSet.getInt("overdue_rank"),
        resultSet.getInt("todo_rank"),
        resultSet.getInt("due_null_rank"));
  }

  private TagCandidate tagCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
    return new TagCandidate(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("canonical_name"),
        resultSet.getString("normalized_name"));
  }

  private GraphDtos.Node memoNode(MemoCandidate memo) {
    return new GraphDtos.Node(
        "memo:" + memo.id(),
        NodeKind.MEMO.name(),
        memo.title(),
        memo.kind(),
        memo.taskStatus() == null ? "NONE" : memo.taskStatus(),
        memo.overdue(),
        memo.pinned());
  }

  private GraphDtos.Node tagNode(TagCandidate tag) {
    return new GraphDtos.Node(
        "tag:" + tag.id(), NodeKind.TAG.name(), tag.canonicalName(), null, null, false, false);
  }

  private GraphDtos.Edge memoTagEdge(UUID memoId, UUID tagId) {
    return new GraphDtos.Edge(
        "memo-tag:" + memoId + ":" + tagId, "memo:" + memoId, "tag:" + tagId, "MEMO_TAG");
  }

  private NodeKind parseKind(String requestedKind) {
    if (requestedKind == null) {
      throw invalidKind();
    }
    try {
      NodeKind kind = NodeKind.valueOf(requestedKind);
      if (!kind.name().equals(requestedKind)) {
        throw invalidKind();
      }
      return kind;
    } catch (IllegalArgumentException exception) {
      throw invalidKind();
    }
  }

  private int validateLimit(int requestedLimit) {
    if (requestedLimit < 1 || requestedLimit > MAX_PAGE_SIZE) {
      throw DomainException.invalid(
          "INVALID_GRAPH_NEIGHBORHOOD_LIMIT", "limit must be between 1 and 20.");
    }
    return requestedLimit;
  }

  private DomainException invalidKind() {
    return DomainException.invalid("INVALID_GRAPH_NODE_KIND", "kind must be MEMO or TAG.");
  }

  private DomainException invalidCursor() {
    return DomainException.invalid(
        "INVALID_GRAPH_CURSOR", "The graph neighborhood cursor is invalid.");
  }

  private enum NodeKind {
    MEMO,
    TAG
  }

  private record MemoCandidate(
      UUID id,
      String title,
      String kind,
      String taskStatus,
      boolean overdue,
      boolean pinned,
      boolean hasTodo,
      Instant nextTodoDue,
      Instant revisionCreatedAt,
      int pinnedRank,
      int overdueRank,
      int todoRank,
      int dueNullRank) {}

  private record TagCandidate(UUID id, String canonicalName, String normalizedName) {}
}
