package local.personalmemo.graph.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict URL-safe cursor codec containing identities and an opaque visible-state change digest,
 * never raw content, graph labels, task fields, due values, or mutable sort values themselves.
 */
@Component
public class GraphNeighborhoodCursorCodec {
  private static final String VERSION = "2";
  private static final int MAX_ENCODED_LENGTH = 1024;
  private static final int MAX_DECODED_LENGTH = 768;
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> FIELDS =
      Set.of(
          "version",
          "ownerId",
          "centerKind",
          "centerId",
          "sortShape",
          "snapshotAsOf",
          "neighborhoodDigest",
          "lastNeighborId");

  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public GraphNeighborhoodCursorCodec() {}

  String encode(
      UUID ownerId,
      String centerKind,
      UUID centerId,
      String sortShape,
      Instant snapshotAsOf,
      String neighborhoodDigest,
      UUID lastNeighborId) {
    if (neighborhoodDigest == null || !DIGEST.matcher(neighborhoodDigest).matches()) {
      throw new IllegalArgumentException("The neighborhood digest must be lowercase SHA-256.");
    }
    ObjectNode root =
        json.createObjectNode()
            .put("version", VERSION)
            .put("ownerId", ownerId.toString())
            .put("centerKind", centerKind)
            .put("centerId", centerId.toString())
            .put("sortShape", sortShape)
            .put("snapshotAsOf", snapshotAsOf.toString())
            .put("neighborhoodDigest", neighborhoodDigest)
            .put("lastNeighborId", lastNeighborId.toString());
    byte[] serialized = root.toString().getBytes(StandardCharsets.UTF_8);
    if (serialized.length > MAX_DECODED_LENGTH) {
      throw new IllegalStateException("The graph neighborhood cursor exceeds its internal bound.");
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(serialized);
  }

  DecodedCursor decode(
      String encoded,
      UUID expectedOwnerId,
      String expectedCenterKind,
      UUID expectedCenterId,
      String expectedSortShape) {
    if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH) {
      throw invalid();
    }

    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(encoded);
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
    if (decoded.length == 0
        || decoded.length > MAX_DECODED_LENGTH
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
      throw invalid();
    }

    JsonNode parsed;
    try {
      parsed = json.readTree(decoded);
    } catch (Exception exception) {
      throw invalid();
    }
    if (!(parsed instanceof ObjectNode root)
        || !Arrays.equals(decoded, root.toString().getBytes(StandardCharsets.UTF_8))
        || !new HashSet<>(root.propertyNames()).equals(FIELDS)
        || !allTextual(root)) {
      throw invalid();
    }

    try {
      String ownerIdValue = root.path("ownerId").asText();
      UUID ownerId = canonicalUuid(ownerIdValue);
      String centerKind = root.path("centerKind").asText();
      UUID centerId = canonicalUuid(root.path("centerId").asText());
      String sortShape = root.path("sortShape").asText();
      String snapshotValue = root.path("snapshotAsOf").asText();
      Instant snapshotAsOf = Instant.parse(snapshotValue);
      String neighborhoodDigest = root.path("neighborhoodDigest").asText();
      UUID lastNeighborId = canonicalUuid(root.path("lastNeighborId").asText());
      if (!VERSION.equals(root.path("version").asText())
          || !snapshotAsOf.toString().equals(snapshotValue)
          || !DIGEST.matcher(neighborhoodDigest).matches()
          || !expectedOwnerId.equals(ownerId)
          || !expectedCenterKind.equals(centerKind)
          || !expectedCenterId.equals(centerId)
          || !expectedSortShape.equals(sortShape)) {
        throw invalid();
      }
      return new DecodedCursor(snapshotAsOf, neighborhoodDigest, lastNeighborId);
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private UUID canonicalUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) {
      throw invalid();
    }
    return parsed;
  }

  private boolean allTextual(ObjectNode root) {
    return FIELDS.stream().allMatch(field -> root.path(field).isTextual());
  }

  private DomainException invalid() {
    return DomainException.invalid(
        "INVALID_GRAPH_CURSOR", "The graph neighborhood cursor is invalid.");
  }

  record DecodedCursor(Instant snapshotAsOf, String neighborhoodDigest, UUID lastNeighborId) {}
}
