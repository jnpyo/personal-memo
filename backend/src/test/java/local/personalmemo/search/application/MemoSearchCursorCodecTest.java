package local.personalmemo.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;

class MemoSearchCursorCodecTest {
  private static final UUID OWNER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID MEMO_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final Instant SNAPSHOT = Instant.parse("2026-08-11T00:00:00Z");
  private static final String QUERY_DIGEST = "01".repeat(32);
  private static final String FILTER_DIGEST = "23".repeat(32);
  private static final String RESULT_DIGEST = "45".repeat(32);

  private final MemoSearchCursorCodec codec = new MemoSearchCursorCodec();

  @Test
  void roundTripsOnlyHashesSnapshotAndIdentities() {
    String encoded =
        codec.encode(
            OWNER_ID,
            QUERY_DIGEST,
            FILTER_DIGEST,
            MemoSearchService.SORT_SHAPE,
            SNAPSHOT,
            RESULT_DIGEST,
            MEMO_ID);

    var decoded =
        codec.decode(encoded, OWNER_ID, QUERY_DIGEST, FILTER_DIGEST, MemoSearchService.SORT_SHAPE);
    String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);

    assertThat(decoded.snapshotAsOf()).isEqualTo(SNAPSHOT);
    assertThat(decoded.resultDigest()).isEqualTo(RESULT_DIGEST);
    assertThat(decoded.lastMemoId()).isEqualTo(MEMO_ID);
    assertThat(raw)
        .contains("\"queryDigest\":\"" + QUERY_DIGEST + "\"")
        .contains("\"filterDigest\":\"" + FILTER_DIGEST + "\"")
        .doesNotContain("queryText", "preview", "title", "canonicalName", "taskState");
  }

  @Test
  void rejectsPoisonNonCanonicalAndReplayedRepresentations() {
    String valid = rawCursor(OWNER_ID.toString(), SNAPSHOT.toString());
    String encoded = base64(valid);
    String duplicate = valid.replace("\"version\":\"1\"", "\"version\":\"1\",\"version\":\"1\"");
    String extra = valid.substring(0, valid.length() - 1) + ",\"extra\":\"x\"}";
    String nonCanonicalInstant = rawCursor(OWNER_ID.toString(), "2026-08-11T00:00:00.000Z");
    String uppercaseOwner = rawCursor(OWNER_ID.toString().toUpperCase(), SNAPSHOT.toString());

    for (String poison :
        new String[] {
          "not-a-cursor",
          encoded + "=",
          base64(duplicate),
          base64(valid + "{}"),
          base64(extra),
          base64(nonCanonicalInstant),
          base64(uppercaseOwner),
          base64(valid.replace(RESULT_DIGEST, "g".repeat(64))),
          base64(valid.replace("\"version\":\"1\"", "\"version\":\"2\"")),
          "a".repeat(1025)
        }) {
      assertInvalid(poison, OWNER_ID, QUERY_DIGEST, FILTER_DIGEST);
    }

    assertInvalid(encoded, UUID.randomUUID(), QUERY_DIGEST, FILTER_DIGEST);
    assertInvalid(encoded, OWNER_ID, "67".repeat(32), FILTER_DIGEST);
    assertInvalid(encoded, OWNER_ID, QUERY_DIGEST, "89".repeat(32));
  }

  private void assertInvalid(
      String encoded, UUID ownerId, String queryDigest, String filterDigest) {
    assertThatThrownBy(
            () ->
                codec.decode(
                    encoded, ownerId, queryDigest, filterDigest, MemoSearchService.SORT_SHAPE))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_SEARCH_CURSOR"));
  }

  private String rawCursor(String ownerId, String snapshot) {
    return "{"
        + "\"version\":\"1\","
        + "\"ownerId\":\""
        + ownerId
        + "\","
        + "\"queryDigest\":\""
        + QUERY_DIGEST
        + "\","
        + "\"filterDigest\":\""
        + FILTER_DIGEST
        + "\","
        + "\"sortShape\":\""
        + MemoSearchService.SORT_SHAPE
        + "\","
        + "\"snapshotAsOf\":\""
        + snapshot
        + "\","
        + "\"resultDigest\":\""
        + RESULT_DIGEST
        + "\","
        + "\"lastMemoId\":\""
        + MEMO_ID
        + "\"}";
  }

  private String base64(String raw) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
