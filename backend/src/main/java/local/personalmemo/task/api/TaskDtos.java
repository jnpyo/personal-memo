package local.personalmemo.task.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class TaskDtos {
  private TaskDtos() {}

  public record View(
      UUID id,
      String title,
      String status,
      Instant dueAt,
      LocalDate dueDate,
      boolean overdue) {}

  public record Update(
      @NotBlank
          @Pattern(
              regexp = "TODO|DONE|CANCELLED",
              message = "must be TODO, DONE, or CANCELLED")
          String status) {}

  public record UpdateView(UUID id, String status, boolean updated) {}
}
