package local.personalmemo.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class TaskStateIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void dateOnlyTaskIsNotOverdueUntilItsLocalCalendarDateHasPassed() throws Exception {
    LocalDate todayInSeoul = LocalDate.now(ZoneId.of("Asia/Seoul"));
    UUID taskId = applyTaskWithDue(todayInSeoul.toString(), "DATE_ONLY");

    assertThat(findTask(taskId).path("overdue").asBoolean()).isFalse();
    assertThat(findTask(taskId).path("dueDate").asText()).isEqualTo(todayInSeoul.toString());
  }

  @Test
  void overdueIsDerivedFromDateAndTodoStateRatherThanPersisted() throws Exception {
    UUID taskId = applyTaskWithDue("2020-01-01", "DATE_ONLY");

    assertThat(
            db.sql(
                    "select count(*) from information_schema.columns "
                        + "where table_schema='public' and table_name='task_details' "
                        + "and column_name='overdue'")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql("select due_local_date from task_details where memo_item_id=:id")
                .param("id", taskId)
                .query(LocalDate.class)
                .single())
        .isEqualTo(LocalDate.of(2020, 1, 1));
    assertThat(
            db.sql("select due_at_utc is null from task_details where memo_item_id=:id")
                .param("id", taskId)
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(findTask(taskId).path("dueDate").asText()).isEqualTo("2020-01-01");
    assertThat(findTask(taskId).path("overdue").asBoolean()).isTrue();

    var done = updateTask(taskId, "DONE");
    assertThat(done.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(done).path("updated").asBoolean()).isTrue();
    assertThat(findTask(taskId).path("status").asText()).isEqualTo("DONE");
    assertThat(findTask(taskId).path("overdue").asBoolean()).isFalse();
    assertThat(
            db.sql("select completed_at is not null from task_details where memo_item_id=:id")
                .param("id", taskId)
                .query(Boolean.class)
                .single())
        .isTrue();

    updateTask(taskId, "TODO");
    assertThat(findTask(taskId).path("overdue").asBoolean()).isTrue();
    assertThat(
            db.sql("select completed_at is null from task_details where memo_item_id=:id")
                .param("id", taskId)
                .query(Boolean.class)
                .single())
        .isTrue();

    var invalid = updateTask(taskId, "OVERDUE");
    assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
    assertThat(findTask(taskId).path("status").asText()).isEqualTo("TODO");

    updateTask(taskId, "CANCELLED");
    assertThat(findTask(taskId).path("status").asText()).isEqualTo("CANCELLED");
    assertThat(findTask(taskId).path("overdue").asBoolean()).isFalse();
  }

  @Test
  void taskStateUpdateIsIdempotentAndRejectsKeyReuseWithAnotherState() throws Exception {
    UUID taskId = applyTaskWithDue("2099-01-01", "DATE_ONLY");

    var first = updateTask(taskId, "DONE", "same-task-update-key");
    var duplicate = updateTask(taskId, "DONE", "same-task-update-key");

    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(duplicate)).isEqualTo(response(first));
    assertThat(findTask(taskId).path("status").asText()).isEqualTo("DONE");

    var mismatch = updateTask(taskId, "CANCELLED", "same-task-update-key");
    assertThat(mismatch.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(mismatch).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(findTask(taskId).path("status").asText()).isEqualTo("DONE");
  }

  @Test
  void exactTimeUsesUtcInstantInsteadOfDateOnlyColumn() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-exact-time", "2020.01.01 18:00 제출");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "start-exact-time", 1)).path("proposalId").asText());
    Map<String, Object> due =
        Map.of(
            "surfaceText", "2020.01.01 18:00",
            "value", "2020-01-01T18:00:00+09:00",
            "precision", "EXACT_TIME",
            "timeZone", "Asia/Seoul",
            "timeSpecified", true);
    var applied = applyProposal(proposalId, "apply-exact-time", 1, "정시 제출", due);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    UUID taskId = db.sql("select memo_item_id from task_details").query(UUID.class).single();

    assertThat(
            db.sql("select due_local_date is null from task_details where memo_item_id=:id")
                .param("id", taskId)
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(
            db.sql("select due_at_utc from task_details where memo_item_id=:id")
                .param("id", taskId)
                .query(Timestamp.class)
                .single()
                .toInstant())
        .isEqualTo(Instant.parse("2020-01-01T09:00:00Z"));
    assertThat(findTask(taskId).path("overdue").asBoolean()).isTrue();
  }

  private UUID applyTaskWithDue(String value, String precision) throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-task-" + memoId, "지난 과제 제출");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "start-task-" + memoId, 1)).path("proposalId").asText());
    Map<String, Object> due =
        Map.of(
            "surfaceText",
            "지난 날짜",
            "value",
            value,
            "precision",
            precision,
            "timeZone",
            "Asia/Seoul",
            "timeSpecified",
            false);
    var applied = applyProposal(proposalId, "apply-task-" + memoId, 1, "지난 과제", due);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    return db.sql("select memo_item_id from task_details").query(UUID.class).single();
  }

  private tools.jackson.databind.JsonNode findTask(UUID taskId) throws Exception {
    var result = mvc.perform(get("/api/v1/tasks")).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    for (var task : response(result)) {
      if (task.path("id").asText().equals(taskId.toString())) {
        return task;
      }
    }
    throw new AssertionError("task not found: " + taskId);
  }

  private org.springframework.test.web.servlet.MvcResult updateTask(UUID taskId, String status)
      throws Exception {
    return updateTask(taskId, status, "task-update-" + UUID.randomUUID());
  }

  private org.springframework.test.web.servlet.MvcResult updateTask(
      UUID taskId, String status, String key) throws Exception {
    return mvc.perform(
            patch("/api/v1/tasks/{id}", taskId)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("status", status))))
        .andReturn();
  }
}
