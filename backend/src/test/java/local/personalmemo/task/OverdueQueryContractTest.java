package local.personalmemo.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OverdueQueryContractTest {

  @Test
  void migrationsDoNotPersistOverdueAsCanonicalState() throws Exception {
    try (var migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
      String allSql =
          migrations
              .filter(path -> path.getFileName().toString().endsWith(".sql"))
              .map(
                  path -> {
                    try {
                      return Files.readString(path);
                    } catch (Exception exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .reduce("", (left, right) -> left + "\n" + right);

      assertThat(allSql).doesNotContainIgnoringCase("overdue boolean");
      assertThat(allSql).doesNotContainIgnoringCase("status overdue");
      assertThat(allSql).contains("status VARCHAR(16)");
      assertThat(allSql).contains("due_local_date DATE");
    }
  }
}
