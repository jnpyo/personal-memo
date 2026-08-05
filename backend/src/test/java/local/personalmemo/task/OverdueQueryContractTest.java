package local.personalmemo.task;
import static org.assertj.core.api.Assertions.assertThat; import java.nio.file.*; import org.junit.jupiter.api.Test;
class OverdueQueryContractTest {@Test void migrationDoesNotPersistOverdueColumn() throws Exception {var sql=Files.readString(Path.of("src/main/resources/db/migration/V2__analysis_and_derived_domain.sql"));assertThat(sql).doesNotContainIgnoringCase("overdue ");assertThat(sql).contains("status VARCHAR(16)");}}

