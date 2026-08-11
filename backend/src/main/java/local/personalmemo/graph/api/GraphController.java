package local.personalmemo.graph.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import local.personalmemo.common.auth.CurrentIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {
  private static final int MAX_HOME_NODES = 100;

  private final JdbcClient db;
  private final CurrentIdentity identity;

  public GraphController(JdbcClient db, CurrentIdentity identity) {
    this.db = db;
    this.identity = identity;
  }

  @GetMapping("/home")
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  ResponseEntity<GraphDtos.Home> home(
      @RequestParam(name = "limit", defaultValue = "100") int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, MAX_HOME_NODES));
    List<MemoCandidate> memoCandidates = findMemoCandidates(limit + 1);
    int preferredMemoBudget = limit == 1 ? 1 : limit - Math.max(1, limit / 5);
    int initialMemoCount = Math.min(memoCandidates.size(), preferredMemoBudget);
    List<MemoCandidate> initialMemos = List.copyOf(memoCandidates.subList(0, initialMemoCount));

    int tagBudget = limit - initialMemos.size();
    List<TagCandidate> initialTagCandidates =
        findTagCandidates(initialMemos.stream().map(MemoCandidate::id).toList(), tagBudget + 1);
    int selectedTagCount = Math.min(initialTagCandidates.size(), tagBudget);

    List<MemoCandidate> selectedMemos = initialMemos;
    List<TagCandidate> tagCandidates = initialTagCandidates;
    if (selectedTagCount > 0) {
      int finalMemoCount = Math.min(memoCandidates.size(), limit - selectedTagCount);
      selectedMemos = List.copyOf(memoCandidates.subList(0, finalMemoCount));
      tagCandidates =
          findTagCandidates(
              selectedMemos.stream().map(MemoCandidate::id).toList(), selectedTagCount + 1);
    } else if (tagBudget > 0 && memoCandidates.size() > initialMemos.size()) {
      int probeMemoCount = Math.min(memoCandidates.size(), limit);
      List<MemoCandidate> probeMemos = List.copyOf(memoCandidates.subList(0, probeMemoCount));
      List<TagCandidate> probeTags =
          findTagCandidates(probeMemos.stream().map(MemoCandidate::id).toList(), 1);
      if (probeTags.isEmpty()) {
        selectedMemos = probeMemos;
        tagCandidates = List.of();
      }
    }
    List<TagCandidate> selectedTags =
        List.copyOf(tagCandidates.subList(0, Math.min(tagCandidates.size(), selectedTagCount)));
    boolean truncated =
        memoCandidates.size() > selectedMemos.size() || tagCandidates.size() > selectedTags.size();

    List<GraphDtos.Node> nodes = new ArrayList<>(limit);
    selectedMemos.stream().map(this::memoNode).forEach(nodes::add);
    selectedTags.stream().map(this::tagNode).forEach(nodes::add);
    List<GraphDtos.Edge> edges =
        findEdges(
            selectedMemos.stream().map(MemoCandidate::id).toList(),
            selectedTags.stream().map(TagCandidate::id).toList());
    UUID projectionVersion = projectionVersion(nodes, edges, truncated);

    GraphDtos.Home home =
        new GraphDtos.Home(List.copyOf(nodes), edges, truncated, projectionVersion);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(home);
  }

  private List<MemoCandidate> findMemoCandidates(int candidateLimit) {
    return db.sql(
            """
            select m.id,
                   m.pinned,
                   latest_application.selection_json ->> 'title' as title,
                   latest_application.selection_json ->> 'selectedType' as kind,
                   task_summary.task_status,
                   coalesce(task_summary.overdue, false) as overdue,
                   coalesce(task_summary.has_todo, false) as has_todo,
                   task_summary.next_todo_due
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
                             (t.due_at_utc is not null and t.due_at_utc < current_timestamp)
                             or (
                               t.due_local_date is not null
                               and t.due_local_date <
                                 (current_timestamp at time zone t.source_time_zone)::date
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
             order by m.pinned desc,
                      coalesce(task_summary.overdue, false) desc,
                      coalesce(task_summary.has_todo, false) desc,
                      task_summary.next_todo_due asc nulls last,
                      current_revision.created_at desc,
                      m.id
             limit :limit
            """)
        .param("ownerId", identity.ownerId())
        .param("limit", candidateLimit)
        .query(
            (resultSet, rowNumber) ->
                new MemoCandidate(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("title"),
                    resultSet.getString("kind"),
                    resultSet.getString("task_status"),
                    resultSet.getBoolean("overdue"),
                    resultSet.getBoolean("pinned")))
        .list();
  }

  private List<TagCandidate> findTagCandidates(List<UUID> memoIds, int candidateLimit) {
    if (memoIds.isEmpty()) {
      return List.of();
    }
    return db.sql(
            """
            select t.id,
                   t.canonical_name,
                   count(distinct i.memo_id) as connected_memo_count
              from tags t
              join item_tags it
                on it.tag_id = t.id
               and it.owner_id = t.owner_id
              join memo_items i
                on i.id = it.memo_item_id
               and i.owner_id = it.owner_id
              join memos m
                on m.id = i.memo_id
               and m.owner_id = i.owner_id
             where t.owner_id = :ownerId
               and t.state = 'ACTIVE'
               and m.status = 'ACTIVE'
               and i.archived_at is null
               and i.memo_id in (:memoIds)
             group by t.id, t.canonical_name
             order by count(distinct i.memo_id) desc,
                      lower(t.canonical_name),
                      t.canonical_name,
                      t.id
             limit :limit
            """)
        .param("ownerId", identity.ownerId())
        .param("memoIds", memoIds)
        .param("limit", candidateLimit)
        .query(
            (resultSet, rowNumber) ->
                new TagCandidate(
                    resultSet.getObject("id", UUID.class), resultSet.getString("canonical_name")))
        .list();
  }

  private List<GraphDtos.Edge> findEdges(List<UUID> memoIds, List<UUID> tagIds) {
    if (memoIds.isEmpty() || tagIds.isEmpty()) {
      return List.of();
    }
    return db.sql(
            """
            select distinct i.memo_id, t.id as tag_id
              from memo_items i
              join item_tags it
                on it.memo_item_id = i.id
               and it.owner_id = i.owner_id
              join tags t
                on t.id = it.tag_id
               and t.owner_id = it.owner_id
             where i.owner_id = :ownerId
               and i.archived_at is null
               and t.state = 'ACTIVE'
               and i.memo_id in (:memoIds)
               and t.id in (:tagIds)
             order by i.memo_id, t.id
            """)
        .param("ownerId", identity.ownerId())
        .param("memoIds", memoIds)
        .param("tagIds", tagIds)
        .query(
            (resultSet, rowNumber) -> {
              UUID memoId = resultSet.getObject("memo_id", UUID.class);
              UUID tagId = resultSet.getObject("tag_id", UUID.class);
              return new GraphDtos.Edge(
                  "memo-tag:" + memoId + ":" + tagId, "memo:" + memoId, "tag:" + tagId, "MEMO_TAG");
            })
        .list();
  }

  private GraphDtos.Node memoNode(MemoCandidate memo) {
    return new GraphDtos.Node(
        "memo:" + memo.id(),
        "MEMO",
        memo.title(),
        memo.kind(),
        memo.taskStatus() == null ? "NONE" : memo.taskStatus(),
        memo.overdue(),
        memo.pinned());
  }

  private GraphDtos.Node tagNode(TagCandidate tag) {
    return new GraphDtos.Node(
        "tag:" + tag.id(), "TAG", tag.canonicalName(), null, null, false, false);
  }

  private UUID projectionVersion(
      List<GraphDtos.Node> nodes, List<GraphDtos.Edge> edges, boolean truncated) {
    StringBuilder signature = new StringBuilder(Boolean.toString(truncated));
    nodes.forEach(node -> signature.append('|').append(node));
    edges.forEach(edge -> signature.append('|').append(edge));
    return UUID.nameUUIDFromBytes(signature.toString().getBytes(StandardCharsets.UTF_8));
  }

  private record MemoCandidate(
      UUID id, String title, String kind, String taskStatus, boolean overdue, boolean pinned) {}

  private record TagCandidate(UUID id, String canonicalName) {}
}
