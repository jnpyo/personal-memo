package local.personalmemo.search.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.search.api.SearchDtos;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MemoSearchRepository {
  private static final UUID ZERO_UUID = new UUID(0, 0);

  private static final String CANDIDATES_CTE =
      """
      with search_candidates as (
        select candidate.*
          from (
            select memo.id as memo_id,
                   memo.current_revision,
                   case
                     when jsonb_typeof(latest_application.selection_json) = 'object'
                      and jsonb_typeof(latest_application.selection_json -> 'title') = 'string'
                      and btrim(latest_application.selection_json ->> 'title') <> ''
                     then latest_application.memo_revision
                     else null
                   end as canonical_revision,
                   case
                     when jsonb_typeof(latest_application.selection_json) = 'object'
                      and jsonb_typeof(latest_application.selection_json -> 'title') = 'string'
                      and btrim(latest_application.selection_json ->> 'title') <> ''
                     then latest_application.selection_json ->> 'title'
                     else null
                   end as title,
                   current_revision.content,
                   current_revision.content_hash,
                   memo.status as lifecycle_status,
                   coalesce(task_summary.task_state, 'NONE') as task_state,
                   coalesce(task_summary.overdue, false) as overdue,
                   memo.pinned,
                   current_revision.created_at as revised_at,
                   (
                     latest_application.selection_json is not null
                     and jsonb_typeof(latest_application.selection_json) = 'object'
                     and jsonb_typeof(latest_application.selection_json -> 'title') = 'string'
                     and strpos(
                       lower(
                         normalize(latest_application.selection_json ->> 'title', NFKC)
                         collate "und-x-icu"
                       ),
                       :textQuery
                     ) > 0
                   ) as title_match,
                   strpos(
                     lower(normalize(current_revision.content, NFKC) collate "und-x-icu"),
                     :textQuery
                   ) > 0
                     as body_match,
                   coalesce(tag_summary.tag_match, false) as tag_match,
                   coalesce(tag_summary.alias_match, false) as alias_match
              from memos memo
              join memo_revisions current_revision
                on current_revision.memo_id = memo.id
               and current_revision.owner_id = memo.owner_id
               and current_revision.revision = memo.current_revision
              left join lateral (
                select application.memo_revision, application.selection_json
                  from analysis_applications application
                 where application.memo_id = memo.id
                   and application.owner_id = memo.owner_id
                   and application.status = 'APPLIED'
                 order by application.applied_at desc, application.id desc
                 limit 1
              ) latest_application on true
              left join lateral (
                select case
                         when bool_or(task.status = 'TODO') then 'TODO'
                         when bool_or(task.status = 'DONE') then 'DONE'
                         when bool_or(task.status = 'CANCELLED') then 'CANCELLED'
                         else null
                       end as task_state,
                       coalesce(
                         bool_or(
                           task.status = 'TODO'
                           and (
                             (task.due_at_utc is not null
                              and task.due_at_utc < cast(:snapshotAsOf as timestamptz))
                             or (
                               task.due_local_date is not null
                               and task.due_local_date <
                                 (cast(:snapshotAsOf as timestamptz)
                                  at time zone task.source_time_zone)::date
                             )
                           )
                         ),
                         false
                       ) as overdue
                  from memo_items item
                  join analysis_applications application
                    on application.id = item.application_id
                   and application.owner_id = item.owner_id
                   and application.status = 'APPLIED'
                  join task_details task
                    on task.memo_item_id = item.id
                   and task.owner_id = item.owner_id
                 where item.memo_id = memo.id
                   and item.owner_id = memo.owner_id
                   and item.archived_at is null
              ) task_summary on true
              left join lateral (
                select coalesce(bool_or(tag.normalized_name = :tagQuery), false) as tag_match,
                       coalesce(
                         bool_or(
                           exists (
                             select 1
                               from tag_aliases alias
                              where alias.owner_id = tag.owner_id
                                and alias.tag_id = tag.id
                                and alias.normalized_alias = :tagQuery
                           )
                         ),
                         false
                       ) as alias_match
                  from memo_items item
                  join analysis_applications application
                    on application.id = item.application_id
                   and application.owner_id = item.owner_id
                   and application.status = 'APPLIED'
                  join item_tags link
                    on link.memo_item_id = item.id
                   and link.owner_id = item.owner_id
                   and link.application_id = item.application_id
                  join tags tag
                    on tag.id = link.tag_id
                   and tag.owner_id = link.owner_id
                   and tag.state = 'ACTIVE'
                 where item.memo_id = memo.id
                   and item.owner_id = memo.owner_id
                   and item.archived_at is null
              ) tag_summary on :tagQueryEligible
             where memo.owner_id = :ownerId
               and memo.status = :lifecycleStatus
               and (
                 :hasRevisedFrom = false
                 or current_revision.created_at >= cast(:revisedFrom as timestamptz)
               )
               and (
                 :hasRevisedBefore = false
                 or current_revision.created_at < cast(:revisedBefore as timestamptz)
               )
          ) candidate
         where (
           candidate.title_match
           or candidate.body_match
           or candidate.tag_match
           or candidate.alias_match
         )
           and (:hasTaskState = false or candidate.task_state = :taskState)
           and (:hasOverdue = false or candidate.overdue = :overdue)
      )
      """;

  private final JdbcClient db;

  public MemoSearchRepository(JdbcClient db) {
    this.db = db;
  }

  public List<Candidate> page(Criteria criteria, Candidate after, int limit) {
    return bind(db.sql(pageSql()), criteria)
        .param("hasCursor", after != null)
        .param("cursorRevisedAt", Timestamp.from(after == null ? Instant.EPOCH : after.revisedAt()))
        .param("cursorMemoId", after == null ? ZERO_UUID : after.memoId())
        .param("limit", limit)
        .query(this::candidate)
        .list();
  }

  static String pageSql() {
    return CANDIDATES_CTE
        + """
        select candidate.*
          from search_candidates candidate
         where :hasCursor = false
            or candidate.revised_at < cast(:cursorRevisedAt as timestamptz)
            or (
              candidate.revised_at = cast(:cursorRevisedAt as timestamptz)
              and candidate.memo_id > cast(:cursorMemoId as uuid)
            )
         order by candidate.revised_at desc, candidate.memo_id
         limit :limit
        """;
  }

  public Optional<Candidate> find(Criteria criteria, UUID memoId) {
    return bind(
            db.sql(
                CANDIDATES_CTE
                    + """
                    select candidate.*
                      from search_candidates candidate
                     where candidate.memo_id = :memoId
                    """),
            criteria)
        .param("memoId", memoId)
        .query(this::candidate)
        .optional();
  }

  public String digest(
      Criteria criteria, String digestShape, String sortShape, String snapshotAsOfText) {
    return bind(db.sql(digestSql()), criteria)
        .param("digestShape", digestShape)
        .param("sortShape", sortShape)
        .param("queryDigest", criteria.queryDigest())
        .param("filterDigest", criteria.filterDigest())
        .param("snapshotAsOfText", snapshotAsOfText)
        .query(String.class)
        .single();
  }

  public Map<UUID, List<SearchDtos.CanonicalTag>> canonicalTags(
      UUID ownerId,
      List<UUID> memoIds,
      boolean tagQueryEligible,
      String tagQuery,
      int perMemoLimit) {
    if (memoIds.isEmpty()) {
      return Map.of();
    }
    List<TagRow> rows =
        db.sql(
                """
                with linked_tags as (
                  select distinct item.memo_id,
                         tag.id,
                         tag.canonical_name,
                         tag.normalized_name,
                         case
                           when :tagQueryEligible and tag.normalized_name = :tagQuery then 0
                           when :tagQueryEligible and exists (
                             select 1
                               from tag_aliases alias
                              where alias.owner_id = tag.owner_id
                                and alias.tag_id = tag.id
                                and alias.normalized_alias = :tagQuery
                           ) then 1
                           else 2
                         end as match_rank
                    from memo_items item
                    join analysis_applications application
                      on application.id = item.application_id
                     and application.owner_id = item.owner_id
                     and application.status = 'APPLIED'
                    join item_tags link
                      on link.memo_item_id = item.id
                     and link.owner_id = item.owner_id
                     and link.application_id = item.application_id
                    join tags tag
                      on tag.id = link.tag_id
                     and tag.owner_id = link.owner_id
                     and tag.state = 'ACTIVE'
                   where item.owner_id = :ownerId
                     and item.memo_id in (:memoIds)
                     and item.archived_at is null
                ), ranked_tags as (
                  select linked_tags.*,
                         row_number() over (
                           partition by memo_id
                           order by match_rank, normalized_name, id
                         ) as tag_rank
                    from linked_tags
                )
                select memo_id, id, canonical_name
                  from ranked_tags
                 where tag_rank <= :perMemoLimit
                 order by memo_id, tag_rank
                """)
            .param("tagQueryEligible", tagQueryEligible)
            .param("tagQuery", tagQuery)
            .param("ownerId", ownerId)
            .param("memoIds", memoIds)
            .param("perMemoLimit", perMemoLimit)
            .query(
                (resultSet, rowNumber) ->
                    new TagRow(
                        resultSet.getObject("memo_id", UUID.class),
                        new SearchDtos.CanonicalTag(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("canonical_name"))))
            .list();
    Map<UUID, List<SearchDtos.CanonicalTag>> byMemo = new LinkedHashMap<>();
    for (TagRow row : rows) {
      byMemo.computeIfAbsent(row.memoId(), ignored -> new ArrayList<>()).add(row.tag());
    }
    byMemo.replaceAll((ignored, tags) -> List.copyOf(tags));
    return Map.copyOf(byMemo);
  }

  static String digestSql() {
    return CANDIDATES_CTE
        + """
        select encode(
                 sha256(
                   convert_to(
                     jsonb_build_array(
                       :digestShape,
                       cast(:ownerId as text),
                       :queryDigest,
                       :filterDigest,
                       :sortShape,
                       :snapshotAsOfText,
                       coalesce(
                         jsonb_agg(
                           jsonb_build_array(
                             cast(candidate.memo_id as text),
                             candidate.current_revision,
                             candidate.canonical_revision,
                             candidate.title,
                             candidate.content_hash,
                             candidate.lifecycle_status,
                             candidate.task_state,
                             candidate.overdue,
                             candidate.pinned,
                             to_char(
                               candidate.revised_at at time zone 'UTC',
                               'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
                             ),
                             candidate.title_match,
                             candidate.body_match,
                             candidate.tag_match,
                             candidate.alias_match,
                             (
                               select coalesce(
                                        jsonb_agg(
                                          jsonb_build_array(
                                            cast(visible_tag.id as text),
                                            visible_tag.canonical_name
                                          )
                                          order by visible_tag.match_rank,
                                                   visible_tag.normalized_name,
                                                   visible_tag.id
                                        ),
                                        '[]'::jsonb
                                      )
                                 from (
                                   select distinct tag.id,
                                          tag.canonical_name,
                                          tag.normalized_name,
                                          case
                                            when :tagQueryEligible
                                             and tag.normalized_name = :tagQuery then 0
                                            when :tagQueryEligible and exists (
                                              select 1
                                                from tag_aliases alias
                                               where alias.owner_id = tag.owner_id
                                                 and alias.tag_id = tag.id
                                                 and alias.normalized_alias = :tagQuery
                                            ) then 1
                                            else 2
                                          end as match_rank
                                     from memo_items item
                                     join analysis_applications application
                                       on application.id = item.application_id
                                      and application.owner_id = item.owner_id
                                      and application.status = 'APPLIED'
                                     join item_tags link
                                       on link.memo_item_id = item.id
                                      and link.owner_id = item.owner_id
                                      and link.application_id = item.application_id
                                     join tags tag
                                       on tag.id = link.tag_id
                                      and tag.owner_id = link.owner_id
                                      and tag.state = 'ACTIVE'
                                    where item.memo_id = candidate.memo_id
                                      and item.owner_id = :ownerId
                                      and item.archived_at is null
                                    order by match_rank, tag.normalized_name, tag.id
                                    limit 8
                                 ) visible_tag
                             )
                           )
                           order by candidate.revised_at desc, candidate.memo_id
                         ),
                         '[]'::jsonb
                       )
                     )::text,
                     'UTF8'
                   )
                 ),
                 'hex'
               ) as result_digest
          from search_candidates candidate
        """;
  }

  private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Criteria criteria) {
    Instant revisedFrom = criteria.revisedFrom() == null ? Instant.EPOCH : criteria.revisedFrom();
    Instant revisedBefore =
        criteria.revisedBefore() == null ? criteria.snapshotAsOf() : criteria.revisedBefore();
    return statement
        .param("ownerId", criteria.ownerId())
        .param("textQuery", criteria.textQuery())
        .param("tagQueryEligible", criteria.tagQueryEligible())
        .param("tagQuery", criteria.tagQuery())
        .param("lifecycleStatus", criteria.lifecycleStatus())
        .param("snapshotAsOf", Timestamp.from(criteria.snapshotAsOf()))
        .param("hasTaskState", criteria.taskState() != null)
        .param("taskState", criteria.taskState() == null ? "NONE" : criteria.taskState())
        .param("hasOverdue", criteria.overdue() != null)
        .param("overdue", Boolean.TRUE.equals(criteria.overdue()))
        .param("hasRevisedFrom", criteria.revisedFrom() != null)
        .param("revisedFrom", Timestamp.from(revisedFrom))
        .param("hasRevisedBefore", criteria.revisedBefore() != null)
        .param("revisedBefore", Timestamp.from(revisedBefore));
  }

  private Candidate candidate(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Candidate(
        resultSet.getObject("memo_id", UUID.class),
        resultSet.getInt("current_revision"),
        resultSet.getObject("canonical_revision", Integer.class),
        resultSet.getString("title"),
        resultSet.getString("content"),
        resultSet.getString("lifecycle_status"),
        resultSet.getString("task_state"),
        resultSet.getBoolean("overdue"),
        resultSet.getBoolean("pinned"),
        resultSet.getTimestamp("revised_at").toInstant(),
        resultSet.getBoolean("title_match"),
        resultSet.getBoolean("body_match"),
        resultSet.getBoolean("tag_match"),
        resultSet.getBoolean("alias_match"));
  }

  public record Criteria(
      UUID ownerId,
      String textQuery,
      boolean tagQueryEligible,
      String tagQuery,
      String lifecycleStatus,
      String taskState,
      Boolean overdue,
      Instant revisedFrom,
      Instant revisedBefore,
      Instant snapshotAsOf,
      String queryDigest,
      String filterDigest) {}

  public record Candidate(
      UUID memoId,
      int currentRevision,
      Integer canonicalRevision,
      String title,
      String content,
      String lifecycleStatus,
      String taskState,
      boolean overdue,
      boolean pinned,
      Instant revisedAt,
      boolean titleMatch,
      boolean bodyMatch,
      boolean tagMatch,
      boolean aliasMatch) {}

  private record TagRow(UUID memoId, SearchDtos.CanonicalTag tag) {}
}
