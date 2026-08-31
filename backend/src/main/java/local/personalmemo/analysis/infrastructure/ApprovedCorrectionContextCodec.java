package local.personalmemo.analysis.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.personalmemo.analysis.domain.ApprovedCorrectionContext;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Strict, deterministic codec for an offset-only approved-correction dispatch snapshot. */
@Component
public final class ApprovedCorrectionContextCodec {
  static final int MAX_ENCODED_BYTES = 2_048;
  private static final Set<String> ROOT_FIELDS = Set.of("version", "signals");
  private static final Set<String> SIGNAL_FIELDS = Set.of("startUtf16", "endUtf16", "approvedKind");

  private final ObjectMapper strictJson =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public String serialize(ApprovedCorrectionContext context) {
    ObjectNode root = strictJson.createObjectNode().put("version", context.version());
    ArrayNode signals = root.putArray("signals");
    for (ApprovedCorrectionContext.Signal signal : context.signals()) {
      signals
          .addObject()
          .put("startUtf16", signal.startUtf16())
          .put("endUtf16", signal.endUtf16())
          .put("approvedKind", signal.approvedKind());
    }
    return root.toString();
  }

  public ApprovedCorrectionContext deserialize(String value) {
    JsonNode parsed = parse(value);
    if (!(parsed instanceof ObjectNode root) || !hasExactly(root, ROOT_FIELDS)) {
      throw invalid();
    }
    JsonNode version = root.path("version");
    JsonNode signalsNode = root.path("signals");
    if (!version.isTextual() || !(signalsNode instanceof ArrayNode signals)) {
      throw invalid();
    }

    List<ApprovedCorrectionContext.Signal> decoded = new ArrayList<>();
    for (JsonNode node : signals) {
      if (!(node instanceof ObjectNode signal) || !hasExactly(signal, SIGNAL_FIELDS)) {
        throw invalid();
      }
      if (!signal.path("startUtf16").isIntegralNumber()
          || !signal.path("startUtf16").canConvertToInt()
          || !signal.path("endUtf16").isIntegralNumber()
          || !signal.path("endUtf16").canConvertToInt()
          || !signal.path("approvedKind").isTextual()) {
        throw invalid();
      }
      try {
        decoded.add(
            new ApprovedCorrectionContext.Signal(
                signal.path("startUtf16").intValue(),
                signal.path("endUtf16").intValue(),
                signal.path("approvedKind").asText()));
      } catch (RuntimeException exception) {
        throw invalid();
      }
    }
    try {
      return new ApprovedCorrectionContext(version.asText(), decoded);
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private JsonNode parse(String value) {
    if (value == null
        || value.length() > MAX_ENCODED_BYTES
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_ENCODED_BYTES) {
      throw invalid();
    }
    try {
      return strictJson.readTree(value);
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private boolean hasExactly(ObjectNode value, Set<String> expected) {
    return new HashSet<>(value.propertyNames()).equals(expected);
  }

  private IllegalStateException invalid() {
    return new IllegalStateException("The durable approved correction context is invalid.");
  }
}
