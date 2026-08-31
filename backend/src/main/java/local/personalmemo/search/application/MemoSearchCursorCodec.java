package local.personalmemo.search.application;

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

/** Strict opaque cursor. It contains hashes and identities, never a query or display value. */
@Component
public class MemoSearchCursorCodec {
  private static final String VERSION = "1";
  private static final int MAX_ENCODED_LENGTH = 1024;
  private static final int MAX_DECODED_LENGTH = 768;
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> FIELDS =
      Set.of(
          "version",
          "ownerId",
          "queryDigest",
          "filterDigest",
          "sortShape",
          "snapshotAsOf",
          "resultDigest",
          "lastMemoId");

  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  String encode(
      UUID ownerId,
      String queryDigest,
      String filterDigest,
      String sortShape,
      Instant snapshotAsOf,
      String resultDigest,
      UUID lastMemoId) {
    requireDigest(queryDigest);
    requireDigest(filterDigest);
    requireDigest(resultDigest);
    ObjectNode root =
        json.createObjectNode()
            .put("version", VERSION)
            .put("ownerId", ownerId.toString())
            .put("queryDigest", queryDigest)
            .put("filterDigest", filterDigest)
            .put("sortShape", sortShape)
            .put("snapshotAsOf", snapshotAsOf.toString())
            .put("resultDigest", resultDigest)
            .put("lastMemoId", lastMemoId.toString());
    byte[] serialized = root.toString().getBytes(StandardCharsets.UTF_8);
    if (serialized.length > MAX_DECODED_LENGTH) {
      throw new IllegalStateException("The memo search cursor exceeds its internal bound.");
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(serialized);
  }

  DecodedCursor decode(
      String encoded,
      UUID expectedOwnerId,
      String expectedQueryDigest,
      String expectedFilterDigest,
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
        || !FIELDS.stream().allMatch(field -> root.path(field).isTextual())) {
      throw invalid();
    }

    try {
      UUID ownerId = canonicalUuid(root.path("ownerId").asText());
      String snapshotValue = root.path("snapshotAsOf").asText();
      Instant snapshotAsOf = Instant.parse(snapshotValue);
      String queryDigest = root.path("queryDigest").asText();
      String filterDigest = root.path("filterDigest").asText();
      String resultDigest = root.path("resultDigest").asText();
      UUID lastMemoId = canonicalUuid(root.path("lastMemoId").asText());
      if (!VERSION.equals(root.path("version").asText())
          || !snapshotAsOf.toString().equals(snapshotValue)
          || !DIGEST.matcher(queryDigest).matches()
          || !DIGEST.matcher(filterDigest).matches()
          || !DIGEST.matcher(resultDigest).matches()
          || !expectedOwnerId.equals(ownerId)
          || !expectedQueryDigest.equals(queryDigest)
          || !expectedFilterDigest.equals(filterDigest)
          || !expectedSortShape.equals(root.path("sortShape").asText())) {
        throw invalid();
      }
      return new DecodedCursor(snapshotAsOf, resultDigest, lastMemoId);
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private void requireDigest(String digest) {
    if (digest == null || !DIGEST.matcher(digest).matches()) {
      throw new IllegalArgumentException("Cursor digests must be lowercase SHA-256.");
    }
  }

  private UUID canonicalUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) {
      throw invalid();
    }
    return parsed;
  }

  private DomainException invalid() {
    return DomainException.invalid("INVALID_SEARCH_CURSOR", "The memo search cursor is invalid.");
  }

  record DecodedCursor(Instant snapshotAsOf, String resultDigest, UUID lastMemoId) {}
}
