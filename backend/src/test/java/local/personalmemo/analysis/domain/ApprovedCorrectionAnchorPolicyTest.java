package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovedCorrectionAnchorPolicyTest {

  @Test
  void returnsOnlySafeExactUniqueUtf16Anchors() {
    String content = "😀 6시 디스코드 접속하기 내일 메모 비밀번호token 계정1234 discord";

    List<ApprovedCorrectionAnchorPolicy.Anchor> anchors =
        ApprovedCorrectionAnchorPolicy.targetAnchors(content);

    assertThat(anchors)
        .extracting(ApprovedCorrectionAnchorPolicy.Anchor::text)
        .containsExactly("디스코드", "접속하기");
    assertThat(anchors.get(0).startUtf16()).isEqualTo(content.indexOf("디스코드"));
    assertThat(anchors.get(1).startUtf16()).isEqualTo(content.indexOf("접속하기"));
    assertThat(ApprovedCorrectionAnchorPolicy.isActionLike(anchors.get(1))).isTrue();
  }

  @Test
  void removesRepeatedTokensAndMalformedUtf16() {
    assertThat(ApprovedCorrectionAnchorPolicy.targetAnchors("접속하기 후 접속하기"))
        .extracting(ApprovedCorrectionAnchorPolicy.Anchor::text)
        .doesNotContain("접속하기");
    assertThat(ApprovedCorrectionAnchorPolicy.targetAnchors("ＡＩ회의 AI회의"))
        .extracting(ApprovedCorrectionAnchorPolicy.Anchor::text)
        .doesNotContain("ＡＩ회의", "AI회의");
    assertThat(ApprovedCorrectionAnchorPolicy.targetAnchors("\uD83D접속하기")).isEmpty();
  }

  @Test
  void sourceProjectionKeepsOnlyNormalizedSafeTokens() {
    assertThat(ApprovedCorrectionAnchorPolicy.normalizedSourceTokens("ＡＩ회의 password 내일"))
        .containsExactly(ApprovedCorrectionAnchorPolicy.normalize("ＡＩ회의"));
    assertThat(ApprovedCorrectionAnchorPolicy.normalizedSourceTokens("ＡＩ회의 AI회의")).isEmpty();
  }

  @Test
  void revalidationRejectsWrongOrSurrogateSplittingSpans() {
    String content = "😀접속하기";
    int start = content.indexOf("접속하기");

    assertThat(
            ApprovedCorrectionAnchorPolicy.isSafeUniqueAnchor(
                content, start, start + "접속하기".length()))
        .isTrue();
    assertThat(ApprovedCorrectionAnchorPolicy.isSafeUniqueAnchor(content, 1, content.length()))
        .isFalse();
    assertThat(ApprovedCorrectionAnchorPolicy.isSafeUniqueAnchor("접속하기 접속하기", 0, "접속하기".length()))
        .isFalse();
  }

  @Test
  void diagnosticsNeverRenderAnchorText() {
    var anchor = ApprovedCorrectionAnchorPolicy.targetAnchors("접속하기").getFirst();

    assertThat(anchor.toString()).isEqualTo("Anchor[redacted]").doesNotContain("접속하기");
  }
}
