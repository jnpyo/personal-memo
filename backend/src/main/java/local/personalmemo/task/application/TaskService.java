package local.personalmemo.task.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.common.DevIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.idempotency.IdempotencyService;
import local.personalmemo.task.api.TaskDtos.Update;
import local.personalmemo.task.api.TaskDtos.UpdateView;
import local.personalmemo.task.api.TaskDtos.View;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
  private static final String UPDATE_OPERATION = "TASK_STATUS_UPDATE";

  private final JdbcClient db;
  private final DevIdentity identity;
  private final IdempotencyService idempotency;

  public TaskService(JdbcClient db, DevIdentity identity, IdempotencyService idempotency) {
    this.db = db;
    this.identity = identity;
    this.idempotency = idempotency;
  }

  @Transactional(readOnly = true)
  public List<View> list() {
    return db.sql(
            """
            select i.id,
                   i.title,
                   t.status,
                   t.due_at_utc,
                   t.due_local_date,
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
              from memo_items i
              join task_details t
                on t.memo_item_id = i.id
               and t.owner_id = i.owner_id
              join memos m
                on m.id = i.memo_id
               and m.owner_id = i.owner_id
             where i.owner_id = :ownerId
               and i.archived_at is null
               and m.status = 'ACTIVE'
             order by t.due_local_date nulls last,
                      t.due_at_utc nulls last,
                      i.created_at desc
            """)
        .param("ownerId", identity.ownerId())
        .query(this::mapView)
        .list();
  }

  @Transactional
  public UpdateView update(UUID taskId, String key, Update request) {
    String requestHash = idempotency.hashRequest(new UpdateRequest(taskId, request));
    Optional<IdempotencyService.StoredResult> replay =
        idempotency.find(UPDATE_OPERATION, key, requestHash);
    if (replay.isPresent()) {
      return idempotency.convert(replay.get().response(), UpdateView.class);
    }

    TaskState current = findOwnedStateForUpdate(taskId);
    if (!"ACTIVE".equals(current.memoStatus())) {
      throw DomainException.conflict("MEMO_NOT_ACTIVE", "The task's memo is not active.");
    }
    Timestamp completedAt =
        "DONE".equals(request.status()) ? Timestamp.from(Instant.now()) : null;
    boolean changed = !current.taskStatus().equals(request.status());
    if (changed) {
      db.sql(
              """
              update task_details
                 set status = :status,
                     completed_at = :completedAt
               where memo_item_id = :taskId
                 and owner_id = :ownerId
                 and exists (
                   select 1
                     from memo_items i
                    where i.id = task_details.memo_item_id
                      and i.owner_id = :ownerId
                      and exists (
                        select 1
                          from memos m
                         where m.id = i.memo_id
                           and m.owner_id = i.owner_id
                           and m.status = 'ACTIVE'
                      )
                 )
              """)
          .param("status", request.status())
          .param("completedAt", completedAt)
          .param("taskId", taskId)
          .param("ownerId", identity.ownerId())
          .update();
    }

    UpdateView response = new UpdateView(taskId, request.status(), changed);
    idempotency.store(UPDATE_OPERATION, key, requestHash, taskId, response);
    return response;
  }

  private TaskState findOwnedStateForUpdate(UUID taskId) {
    return db.sql(
            """
            select t.status as task_status,
                   m.status as memo_status
              from task_details t
              join memo_items i
                on i.id = t.memo_item_id
               and i.owner_id = t.owner_id
              join memos m
                on m.id = i.memo_id
               and m.owner_id = i.owner_id
             where t.memo_item_id = :taskId
               and t.owner_id = :ownerId
             for update of t, m
            """)
        .param("taskId", taskId)
        .param("ownerId", identity.ownerId())
        .query(
            (resultSet, rowNumber) ->
                new TaskState(
                    resultSet.getString("task_status"),
                    resultSet.getString("memo_status")))
        .optional()
        .orElseThrow(() -> DomainException.notFound("Task"));
  }

  private View mapView(ResultSet resultSet, int rowNumber) throws SQLException {
    Timestamp dueAt = resultSet.getTimestamp("due_at_utc");
    java.sql.Date dueDate = resultSet.getDate("due_local_date");
    return new View(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("title"),
        resultSet.getString("status"),
        dueAt == null ? null : dueAt.toInstant(),
        dueDate == null ? null : dueDate.toLocalDate(),
        resultSet.getBoolean("overdue"));
  }

  private record UpdateRequest(UUID taskId, Update request) {}

  private record TaskState(String taskStatus, String memoStatus) {}
}
