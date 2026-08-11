package local.personalmemo.graph.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;

class GraphNeighborhoodCursorCodecTest {
  private static final UUID OWNER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID CENTER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID NEIGHBOR_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final Instant SNAPSHOT = Instant.parse("2026-08-11T00:00:00Z");
  private static final String DIGEST = "abcdef0123456789".repeat(4);

  private final GraphNeighborhoodCursorCodec codec = new GraphNeighborhoodCursorCodec();

  @Test
  void roundTripsOnlyBoundedIdentityAndOpaqueDigestWithoutGraphOrTaskValues() {
    String encoded =
        codec.encode(
            OWNER_ID,
            "TAG",
            CENTER_ID,
            GraphNeighborhoodService.MEMO_SORT_SHAPE,
            SNAPSHOT,
            DIGEST,
            NEIGHBOR_ID);

    var decoded =
        codec.decode(encoded, OWNER_ID, "TAG", CENTER_ID, GraphNeighborhoodService.MEMO_SORT_SHAPE);
    String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);

    assertThat(decoded.snapshotAsOf()).isEqualTo(SNAPSHOT);
    assertThat(decoded.neighborhoodDigest()).isEqualTo(DIGEST);
    assertThat(decoded.lastNeighborId()).isEqualTo(NEIGHBOR_ID);
    assertThat(raw)
        .contains("\"version\":\"2\"")
        .contains("\"snapshotAsOf\":\"2026-08-11T00:00:00Z\"")
        .contains("\"neighborhoodDigest\":\"" + DIGEST + "\"")
        .contains("\"lastNeighborId\":\"" + NEIGHBOR_ID + "\"")
        .doesNotContain(
            "title",
            "canonicalName",
            "normalizedName",
            "dueAt",
            "nextDue",
            "pinnedRank",
            "todoRank",
            "revisionCreatedAt");
  }

  @Test
  void rejectsDuplicateTrailingBomNonCanonicalAndMismatchedCursorRepresentations() {
    String valid = rawCursor(OWNER_ID.toString(), CENTER_ID.toString(), SNAPSHOT.toString());
    byte[] validBytes = valid.getBytes(StandardCharsets.UTF_8);
    byte[] bom = new byte[validBytes.length + 3];
    bom[0] = (byte) 0xEF;
    bom[1] = (byte) 0xBB;
    bom[2] = (byte) 0xBF;
    System.arraycopy(validBytes, 0, bom, 3, validBytes.length);

    String duplicateVersion =
        valid.replace("\"version\":\"2\"", "\"version\":\"2\",\"version\":\"2\"");
    String unknownField = valid.substring(0, valid.length() - 1) + ",\"extra\":\"x\"}";
    String uppercaseOwner =
        rawCursor(OWNER_ID.toString().toUpperCase(), CENTER_ID.toString(), SNAPSHOT.toString());
    String shortOwner = rawCursor("a-a-a-a-a", CENTER_ID.toString(), SNAPSHOT.toString());
    String nonCanonicalInstant =
        rawCursor(OWNER_ID.toString(), CENTER_ID.toString(), "2026-08-11T00:00:00.000Z");
    String uppercaseDigest = valid.replace(DIGEST, DIGEST.toUpperCase());
    String shortDigest = valid.replace(DIGEST, "abcdef");
    String nonHexDigest = valid.replace(DIGEST, "g".repeat(64));
    String oldVersion = valid.replace("\"version\":\"2\"", "\"version\":\"1\"");
    String validEncoded = base64(validBytes);

    for (String poison :
        new String[] {
          base64(duplicateVersion.getBytes(StandardCharsets.UTF_8)),
          base64((valid + "{}").getBytes(StandardCharsets.UTF_8)),
          base64(bom),
          validEncoded + "=",
          base64(unknownField.getBytes(StandardCharsets.UTF_8)),
          base64(uppercaseOwner.getBytes(StandardCharsets.UTF_8)),
          base64(shortOwner.getBytes(StandardCharsets.UTF_8)),
          base64(nonCanonicalInstant.getBytes(StandardCharsets.UTF_8)),
          base64(uppercaseDigest.getBytes(StandardCharsets.UTF_8)),
          base64(shortDigest.getBytes(StandardCharsets.UTF_8)),
          base64(nonHexDigest.getBytes(StandardCharsets.UTF_8)),
          base64(oldVersion.getBytes(StandardCharsets.UTF_8)),
          "a".repeat(1025)
        }) {
      assertInvalid(poison);
    }

    assertThatThrownBy(
            () ->
                codec.decode(
                    validEncoded,
                    OWNER_ID,
                    "MEMO",
                    CENTER_ID,
                    GraphNeighborhoodService.MEMO_SORT_SHAPE))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_GRAPH_CURSOR"));

    assertThatThrownBy(
            () ->
                codec.encode(
                    OWNER_ID,
                    "TAG",
                    CENTER_ID,
                    GraphNeighborhoodService.MEMO_SORT_SHAPE,
                    SNAPSHOT,
                    DIGEST.toUpperCase(),
                    NEIGHBOR_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void assertInvalid(String encoded) {
    assertThatThrownBy(
            () ->
                codec.decode(
                    encoded, OWNER_ID, "TAG", CENTER_ID, GraphNeighborhoodService.MEMO_SORT_SHAPE))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_GRAPH_CURSOR"));
  }

  private String rawCursor(String ownerId, String centerId, String snapshot) {
    return "{"
        + "\"version\":\"2\","
        + "\"ownerId\":\""
        + ownerId
        + "\","
        + "\"centerKind\":\"TAG\","
        + "\"centerId\":\""
        + centerId
        + "\","
        + "\"sortShape\":\""
        + GraphNeighborhoodService.MEMO_SORT_SHAPE
        + "\","
        + "\"snapshotAsOf\":\""
        + snapshot
        + "\","
        + "\"neighborhoodDigest\":\""
        + DIGEST
        + "\","
        + "\"lastNeighborId\":\""
        + NEIGHBOR_ID
        + "\"}";
  }

  private String base64(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }
}
