package local.personalmemo.analysis.infrastructure;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class Draft202012AnalysisProposalSchemaValidator
    implements AnalysisProposalSchemaValidator {
  static final String SCHEMA_RESOURCE = "/contracts/analysis-proposal.schema.json";
  static final int MAX_PROPOSAL_JSON_BYTES = 64 * 1024;
  static final int MAX_PROVIDER_METADATA_JSON_BYTES = 8 * 1024;
  private static final String INVALID_MESSAGE =
      "The analysis proposal does not match schema version 1.";

  private final Schema schema;

  public Draft202012AnalysisProposalSchemaValidator() {
    this.schema = loadSchema();
  }

  @Override
  public void validate(JsonNode proposal) {
    if (proposal == null) {
      fail();
    }
    try {
      requireSerializedSize(proposal, MAX_PROPOSAL_JSON_BYTES);
      JsonNode providerMetadata = proposal.get("providerMetadata");
      if (providerMetadata != null) {
        requireSerializedSize(providerMetadata, MAX_PROVIDER_METADATA_JSON_BYTES);
      }
      if (!schema.validate(proposal).isEmpty()) {
        fail();
      }
    } catch (DomainException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      fail();
    }
  }

  private static void requireSerializedSize(JsonNode value, int maximumBytes) {
    int encodedBytes = value.toString().getBytes(StandardCharsets.UTF_8).length;
    if (encodedBytes > maximumBytes) {
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
        Draft202012AnalysisProposalSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Analysis proposal schema resource is missing.");
      }
      Schema loaded = registry.getSchema(input);
      loaded.initializeValidators();
      return loaded;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Analysis proposal schema could not be loaded.", exception);
    }
  }

  private static void fail() {
    throw DomainException.invalid("INVALID_ANALYSIS_PROPOSAL", INVALID_MESSAGE);
  }
}
