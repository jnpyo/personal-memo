package local.personalmemo.graph.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.common.DevIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {
  private static final int MAX_HOME_NODES = 100;

  private final JdbcClient db;
  private final DevIdentity identity;

  public GraphController(JdbcClient db, DevIdentity identity) {
    this.db = db;
    this.identity = identity;
  }

  @GetMapping("/home")
  Map<String, Object> home(@RequestParam(defaultValue = "100") int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, MAX_HOME_NODES));
    var nodes = new ArrayList<Map<String, Object>>();
    var edges = new ArrayList<Map<String, Object>>();

    List<UUID> memoIds = addMemoNodes(nodes, boundedLimit);
    addTagNodesAndEdges(nodes, edges, memoIds, boundedLimit);

    return Map.of(
        "nodes", nodes,
        "edges", edges,
        "truncated", nodes.size() >= boundedLimit,
        "projectionVersion", UUID.randomUUID());
  }

  private List<UUID> addMemoNodes(ArrayList<Map<String, Object>> nodes, int limit) {
    List<UUID> memoIds = new ArrayList<>();
    db.sql(
            """
            select distinct on (m.id)
                   m.id,
                   i.title,
                   i.kind,
                   t.status,
                   (
                     t.status = 'TODO'
                     and (
                       (t.due_at_utc is not null and t.due_at_utc < current_timestamp)
                       or (
                         t.due_local_date is not null
                         and t.due_local_date <
                           (current_timestamp at time zone t.source_time_zone)::date
                       )
                     )
                   ) as overdue
              from memos m
              join memo_items i
                on i.memo_id = m.id
               and i.owner_id = m.owner_id
               and i.archived_at is null
              left join task_details t on t.memo_item_id = i.id
             where m.owner_id = :ownerId
               and m.status = 'ACTIVE'
             order by m.id,
                      case when t.status = 'TODO' then 0 else 1 end,
                      i.created_at desc
             limit :limit
            """)
        .param("ownerId", identity.ownerId())
        .param("limit", limit)
        .query(
            (resultSet, rowNumber) -> {
              Map<String, Object> node = new LinkedHashMap<>();
              UUID memoId = resultSet.getObject("id", UUID.class);
              memoIds.add(memoId);
              node.put("id", "memo:" + memoId);
              node.put("kind", "MEMO");
              node.put("label", resultSet.getString("title"));
              node.put("memoType", resultSet.getString("kind"));
              node.put(
                  "taskState",
                  resultSet.getString("status") == null
                      ? "NONE"
                      : resultSet.getString("status"));
              node.put("overdue", resultSet.getBoolean("overdue"));
              nodes.add(node);
              return rowNumber;
            })
        .list();
    return List.copyOf(memoIds);
  }

  private void addTagNodesAndEdges(
      ArrayList<Map<String, Object>> nodes,
      ArrayList<Map<String, Object>> edges,
      List<UUID> memoIds,
      int limit) {
    int remaining = limit - nodes.size();
    if (remaining <= 0 || memoIds.isEmpty()) {
      return;
    }

    var addedTagIds = new HashSet<String>();
    db.sql(
            """
            select distinct t.id, t.canonical_name, i.memo_id
              from tags t
              join item_tags it on it.tag_id = t.id
              join memo_items i
                on i.id = it.memo_item_id
               and i.owner_id = t.owner_id
              join memos m
                on m.id = i.memo_id
               and m.owner_id = i.owner_id
             where t.owner_id = :ownerId
               and t.state = 'ACTIVE'
               and m.status = 'ACTIVE'
               and i.memo_id in (:memoIds)
             limit :limit
            """)
        .param("ownerId", identity.ownerId())
        .param("memoIds", memoIds)
        .param("limit", remaining)
        .query(
            (resultSet, rowNumber) -> {
              String tagId = resultSet.getString("id");
              String memoId = resultSet.getString("memo_id");
              if (addedTagIds.add(tagId)) {
                nodes.add(
                    Map.of(
                        "id", "tag:" + tagId,
                        "kind", "TAG",
                        "label", resultSet.getString("canonical_name")));
              }
              edges.add(
                  Map.of(
                      "id", "memo-tag:" + memoId + ":" + tagId,
                      "source", "memo:" + memoId,
                      "target", "tag:" + tagId,
                      "kind", "MEMO_TAG"));
              return rowNumber;
            })
        .list();
  }
}
