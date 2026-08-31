package local.personalmemo.analysis.infrastructure;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import local.personalmemo.analysis.domain.LocalDecisionEvidenceSchemaValidator;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class Draft202012LocalDecisionEvidenceSchemaValidator
    implements LocalDecisionEvidenceSchemaValidator {
  static final String SCHEMA_RESOURCE = "/contracts/local-decision-evidence.schema.json";
  static final int MAX_EVIDENCE_JSON_BYTES = 16 * 1024;
  private static final String INVALID_MESSAGE = "The local decision evidence is invalid.";

  private final Schema schema;

  public Draft202012LocalDecisionEvidenceSchemaValidator() {
    this.schema = loadSchema();
  }

  @Override
  public void validate(JsonNode evidence) {
    if (evidence == null) {
      fail();
    }
    try {
      int encodedBytes = evidence.toString().getBytes(StandardCharsets.UTF_8).length;
      if (encodedBytes > MAX_EVIDENCE_JSON_BYTES || !schema.validate(evidence).isEmpty()) {
        fail();
      }
    } catch (DomainException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      fail();
    }
  }

  private static Schema loadSchema() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).failFast(true).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));

    try (InputStream input =
        Draft202012LocalDecisionEvidenceSchemaValidator.class.getResourceAsStream(
            SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Local decision evidence schema resource is missing.");
      }
      Schema loaded = registry.getSchema(input);
      loaded.initializeValidators();
      return loaded;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "Local decision evidence schema could not be loaded.", exception);
    }
  }

  static void fail() {
    throw DomainException.invalid("INVALID_LOCAL_DECISION_EVIDENCE", INVALID_MESSAGE);
  }
}
