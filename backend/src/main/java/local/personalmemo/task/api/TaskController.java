package local.personalmemo.task.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import local.personalmemo.task.application.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
  private final TaskService service;

  public TaskController(TaskService service) {
    this.service = service;
  }

  @GetMapping
  List<TaskDtos.View> list() {
    return service.list();
  }

  @PatchMapping("/{id}")
  TaskDtos.UpdateView update(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody TaskDtos.Update body) {
    return service.update(id, key, body);
  }
}
