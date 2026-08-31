package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import local.personalmemo.analysis.domain.TagRetrievalContext;
import local.personalmemo.analysis.domain.TagRetrievalContext.Candidate;
import local.personalmemo.analysis.domain.TagRetrievalContext.MatchKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TagRetrievalContextCodecTest {
  private static final UUID CANONICAL_TAG_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000016");
  private static final UUID ALIAS_TAG_ID = UUID.fromString("20000000-0000-0000-0000-000000000017");

  private final TagRetrievalContextCodec codec = new TagRetrievalContextCodec(new ObjectMapper());

  @Test
  void roundTripsTheStrictDeterministicRepresentation() {
    TagRetrievalContext context =
        new TagRetrievalContext(
            TagRetrievalContext.CURRENT_VERSION,
            List.of(
                new Candidate(1, CANONICAL_TAG_ID, "운영체제", null, MatchKind.CANONICAL, 0),
                new Candidate(2, ALIAS_TAG_ID, "프로젝트", "업무", MatchKind.ALIAS, 3)));

    String encoded = codec.serialize(context);
    TagRetrievalContext decoded = codec.deserialize(encoded);

    assertThat(decoded).isEqualTo(context);
    assertThat(codec.serialize(decoded)).isEqualTo(encoded);
    assertThat(encoded)
        .isEqualTo(
            "{\"version\":\"tag-alias-exact-k8-v1\",\"candidates\":["
                + "{\"rank\":1,\"existingTagId\":\"20000000-0000-0000-0000-000000000016\","
                + "\"canonicalName\":\"운영체제\",\"matchedAlias\":null,\"matchKind\":\"CANONICAL\","
                + "\"sourceCandidateIndex\":0},"
                + "{\"rank\":2,\"existingTagId\":\"20000000-0000-0000-0000-000000000017\","
                + "\"canonicalName\":\"프로젝트\",\"matchedAlias\":\"업무\",\"matchKind\":\"ALIAS\","
                + "\"sourceCandidateIndex\":3}]}");
  }

  @Test
  void rejectsMalformedJsonAndNonObjectRoots() {
    assertInvalid(null);
    assertInvalid("not-json");
    assertInvalid("[]");
    assertInvalid("null");
  }

  @Test
  void requiresExactlyTheRootAndCandidateFields() {
    String valid = validJson();

    assertInvalid(valid.replace(",\"candidates\"", ",\"unexpected\":true,\"candidates\""));
    assertInvalid(valid.replace("\"version\":\"tag-alias-exact-k8-v1\",", ""));
    assertInvalid(valid.replace(",\"sourceCandidateIndex\":0", ""));
    assertInvalid(
        valid.replace(",\"sourceCandidateIndex\":0", ",\"extra\":0,\"sourceCandidateIndex\":0"));
  }

  @Test
  void rejectsWrongFieldTypesAndUnsupportedValues() {
    String valid = validJson();

    assertInvalid(valid.replace("\"tag-alias-exact-k8-v1\"", "1"));
    assertInvalid(
        valid.replace("\"candidates\":[", "\"candidates\":\"not-an-array\",\"ignored\":["));
    assertInvalid(valid.replace("\"rank\":1", "\"rank\":1.5"));
    assertInvalid(valid.replace(CANONICAL_TAG_ID.toString(), "not-a-uuid"));
    assertInvalid(valid.replace("\"canonicalName\":\"운영체제\"", "\"canonicalName\":null"));
    assertInvalid(valid.replace("\"matchedAlias\":null", "\"matchedAlias\":7"));
    assertInvalid(valid.replace("\"CANONICAL\"", "\"UNKNOWN\""));
    assertInvalid(valid.replace("\"sourceCandidateIndex\":0", "\"sourceCandidateIndex\":0.5"));
    assertInvalid(valid.replace("\"tag-alias-exact-k8-v1\"", "\"future-v2\""));
  }

  @Test
  void rejectsIntegralNumbersThatCannotBeRepresentedAsJavaInts() {
    String valid = validJson();

    assertInvalid(valid.replace("\"rank\":1", "\"rank\":4294967297"));
    assertInvalid(
        valid.replace("\"sourceCandidateIndex\":0", "\"sourceCandidateIndex\":4294967296"));
  }

  @Test
  void rejectsDomainInvalidOrderingDuplicatesAndAliasCoherence() {
    String first = validJson();
    String nonSequential = first.replace("\"rank\":1", "\"rank\":2");
    String duplicate =
        first
            .replace("]}", "," + first.substring(first.indexOf('{', 1), first.length() - 2) + "]}")
            .replaceFirst("\\\"rank\\\":1", "\\\"rank\\\":2");

    assertInvalid(nonSequential);
    assertInvalid(duplicate);
    assertInvalid(first.replace("\"matchedAlias\":null", "\"matchedAlias\":\"별칭\""));
    assertInvalid(first.replace("\"CANONICAL\"", "\"ALIAS\""));
  }

  private String validJson() {
    return codec.serialize(
        new TagRetrievalContext(
            TagRetrievalContext.CURRENT_VERSION,
            List.of(new Candidate(1, CANONICAL_TAG_ID, "운영체제", null, MatchKind.CANONICAL, 0))));
  }

  private void assertInvalid(String value) {
    assertThatThrownBy(() -> codec.deserialize(value))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The durable tag retrieval context is invalid.");
  }
}
