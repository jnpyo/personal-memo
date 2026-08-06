package local.personalmemo.graph.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import local.personalmemo.common.auth.CurrentIdentity;
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
  GraphDtos.Home home(@RequestParam(name = "limit", defaultValue = "100") int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, MAX_HOME_NODES));
    List<MemoCandidate> memoCandidates = findMemoCandidates(limit + 1);
    boolean memoOverflow = memoCandidates.size() > limit;
    List<MemoCandidate> selectedMemos =
        List.copyOf(memoCandidates.subList(0, Math.min(memoCandidates.size(), limit)));

    int remainingTagBudget = limit - selectedMemos.size();
    List<TagCandidate> tagCandidates =
        memoOverflow
            ? List.of()
            : findTagCandidates(
                selectedMemos.stream().map(MemoCandidate::id).toList(), remainingTagBudget + 1);
    boolean truncated = memoOverflow || tagCandidates.size() > remainingTagBudget;
    List<TagCandidate> selectedTags =
        List.copyOf(tagCandidates.subList(0, Math.min(tagCandidates.size(), remainingTagBudget)));

    List<GraphDtos.Node> nodes = new ArrayList<>(limit);
    selectedMemos.stream().map(this::memoNode).forEach(nodes::add);
    selectedTags.stream().map(this::tagNode).forEach(nodes::add);
    List<GraphDtos.Edge> edges =
        findEdges(
            selectedMemos.stream().map(MemoCandidate::id).toList(),
            selectedTags.stream().map(TagCandidate::id).toList());
    UUID projectionVersion = projectionVersion(nodes, edges, truncated);

    return new GraphDtos.Home(List.copyOf(nodes), edges, truncated, projectionVersion);
  }

  private List<MemoCandidate> findMemoCandidates(int candidateLimit) {
    return db.sql(
            """
            select m.id,
                   selected_item.title,
                   selected_item.kind,
                   selected_item.task_status,
                   selected_item.overdue
              from memos m
              join lateral (
                select i.title,
                       i.kind,
                       t.status as task_status,
                       coalesce(
                         t.status = 'TODO'
                         and (
                           (t.due_at_utc is not null and t.due_at_utc < current_timestamp)
                           or (
                             t.due_local_date is not null
                             and t.due_local_date <
                               (current_timestamp at time zone t.source_time_zone)::date
                           )
                         ),
                         false
                       ) as overdue
                  from memo_items i
                  left join task_details t
                    on t.memo_item_id = i.id
                   and t.owner_id = i.owner_id
                 where i.memo_id = m.id
                   and i.owner_id = m.owner_id
                   and i.archived_at is null
                 order by case when t.status = 'TODO' then 0 else 1 end,
                          i.created_at desc,
                          i.id
                 limit 1
              ) selected_item on true
             where m.owner_id = :ownerId
               and m.status = 'ACTIVE'
             order by m.updated_at desc, m.id
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
                    resultSet.getBoolean("overdue")))
        .list();
  }

  private List<TagCandidate> findTagCandidates(List<UUID> memoIds, int candidateLimit) {
    if (memoIds.isEmpty()) {
      return List.of();
    }
    return db.sql(
            """
            select t.id, t.canonical_name
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
             order by lower(t.canonical_name), t.canonical_name, t.id
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
        memo.overdue());
  }

  private GraphDtos.Node tagNode(TagCandidate tag) {
    return new GraphDtos.Node("tag:" + tag.id(), "TAG", tag.canonicalName(), null, null, false);
  }

  private UUID projectionVersion(
      List<GraphDtos.Node> nodes, List<GraphDtos.Edge> edges, boolean truncated) {
    StringBuilder signature = new StringBuilder(Boolean.toString(truncated));
    nodes.forEach(node -> signature.append('|').append(node));
    edges.forEach(edge -> signature.append('|').append(edge));
    return UUID.nameUUIDFromBytes(signature.toString().getBytes(StandardCharsets.UTF_8));
  }

  private record MemoCandidate(
      UUID id, String title, String kind, String taskStatus, boolean overdue) {}

  private record TagCandidate(UUID id, String canonicalName) {}
}
