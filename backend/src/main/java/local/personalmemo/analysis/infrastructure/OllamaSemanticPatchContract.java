package local.personalmemo.analysis.infrastructure;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class OllamaSemanticPatchContract {
  static final String SCHEMA_RESOURCE = "/local-model/ollama-semantic-patch.schema.json";
  private static final int MAX_SCHEMA_BYTES = 16 * 1024;

  private final ObjectNode formatSchema;
  private final Schema validator;

  OllamaSemanticPatchContract(ObjectMapper json) {
    Objects.requireNonNull(json, "json");
    byte[] encodedSchema = loadBoundedSchema();
    try {
      var parsed = json.readTree(encodedSchema);
      if (!(parsed instanceof ObjectNode object)) {
        throw new IllegalStateException("The local-model patch schema is not an object.");
      }
      formatSchema = object.deepCopy();
      SchemaRegistryConfig config =
          SchemaRegistryConfig.builder().formatAssertionsEnabled(true).failFast(true).build();
      SchemaRegistry registry =
          SchemaRegistry.withDefaultDialect(
              SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
      validator = registry.getSchema(new ByteArrayInputStream(encodedSchema));
      validator.initializeValidators();
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "The local-model patch schema could not be loaded.", exception);
    }
  }

  ObjectNode formatSchema() {
    return formatSchema.deepCopy();
  }

  boolean isValid(ObjectNode patch) {
    try {
      return patch != null && validator.validate(patch).isEmpty();
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static byte[] loadBoundedSchema() {
    try (InputStream input =
        OllamaSemanticPatchContract.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("The local-model patch schema resource is missing.");
      }
      byte[] encoded = input.readNBytes(MAX_SCHEMA_BYTES + 1);
      if (encoded.length > MAX_SCHEMA_BYTES) {
        throw new IllegalStateException("The local-model patch schema is too large.");
      }
      return encoded;
    } catch (IOException exception) {
      throw new IllegalStateException(
          "The local-model patch schema could not be loaded.", exception);
    }
  }
}
