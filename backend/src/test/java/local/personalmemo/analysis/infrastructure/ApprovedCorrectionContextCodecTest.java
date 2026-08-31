package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import local.personalmemo.analysis.domain.ApprovedCorrectionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovedCorrectionContextCodecTest {
  private final ApprovedCorrectionContextCodec codec = new ApprovedCorrectionContextCodec();

  @Test
  void roundTripsAnExactDeterministicOffsetOnlyShape() {
    ApprovedCorrectionContext context =
        new ApprovedCorrectionContext(
            ApprovedCorrectionContext.CURRENT_VERSION,
            List.of(new ApprovedCorrectionContext.Signal(8, 12, "TASK")));
    String encoded = codec.serialize(context);

    assertThat(encoded)
        .isEqualTo(
            "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[{\"startUtf16\":8,\"endUtf16\":12,\"approvedKind\":\"TASK\"}]}")
        .doesNotContain("memoId", "ownerId", "applicationId", "raw", "hash");
    assertThat(codec.deserialize(encoded)).isEqualTo(context);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "null",
        "[]",
        "{}",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[]} trailing",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[]}",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[],\"extra\":true}",
        "{\"version\":1,\"signals\":[]}",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":{}}",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[{\"startUtf16\":0,\"endUtf16\":4,\"approvedKind\":\"TASK\",\"extra\":true}]}",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[{\"startUtf16\":0.0,\"endUtf16\":4,\"approvedKind\":\"TASK\"}]}",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[{\"startUtf16\":2147483648,\"endUtf16\":2147483652,\"approvedKind\":\"TASK\"}]}",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[{\"startUtf16\":0,\"endUtf16\":4,\"approvedKind\":\"UNKNOWN\"}]}",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[{\"startUtf16\":0,\"endUtf16\":4,\"approvedKind\":\"TASK\"},{\"startUtf16\":0,\"endUtf16\":4,\"approvedKind\":\"EVENT\"}]}",
        "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[{\"startUtf16\":0,\"endUtf16\":4,\"approvedKind\":\"TASK\"},{\"startUtf16\":4,\"endUtf16\":8,\"approvedKind\":\"EVENT\"},{\"startUtf16\":8,\"endUtf16\":12,\"approvedKind\":\"IDEA\"},{\"startUtf16\":12,\"endUtf16\":16,\"approvedKind\":\"RECORD\"}]}",
      })
  void rejectsMalformedOrNonCanonicalShapes(String encoded) {
    assertThatThrownBy(() -> codec.deserialize(encoded))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The durable approved correction context is invalid.");
  }

  @Test
  void rejectsAnOversizedDurableValueBeforeParsing() {
    assertThat(ApprovedCorrectionContextCodec.MAX_ENCODED_BYTES).isEqualTo(2_048);
    String oversized =
        " ".repeat(ApprovedCorrectionContextCodec.MAX_ENCODED_BYTES)
            + "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[]}";

    assertThatThrownBy(() -> codec.deserialize(oversized))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The durable approved correction context is invalid.");
  }
}
