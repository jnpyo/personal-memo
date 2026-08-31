package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KoreanItemExtractorTest {
  private static final Instant BASE = Instant.parse("2026-08-05T02:00:00Z");
  private static final String SEOUL = "Asia/Seoul";

  private final KoreanDateParser dates = new KoreanDateParser();
  private final KoreanItemExtractor extractor = new KoreanItemExtractor();

  @Test
  void extractsADateStrippedTaskWithAnOriginalUtf16Span() {
    String content = "2026.09.14 14:30 치과 예약 확인";

    var result = extract(content);

    assertThat(result.detectedItems()).hasSize(1);
    var item = result.detectedItems().getFirst();
    assertThat(item.kind()).isEqualTo("TASK");
    assertThat(item.title()).isEqualTo("치과 예약 확인");
    assertThat(item.action()).isEqualTo("확인");
    assertThat(item.object()).isEqualTo("치과 예약");
    assertThat(item.startOffset()).isEqualTo(17);
    assertThat(item.endOffset()).isEqualTo(25);
    assertThat(source(content, item)).isEqualTo("치과 예약 확인");
  }

  @Test
  void emitsEverySequentialActionAsASourceAlignedFacet() {
    String content = "책 읽고 핵심 요약하고 도표 만들기";

    var result = extract(content);

    assertThat(result.detectedItems()).hasSize(3);
    assertThat(result.detectedItems())
        .extracting(KoreanItemExtractor.ExtractedItem::title)
        .containsExactly("책 읽기", "핵심 요약하기", "도표 만들기");
    assertThat(result.detectedItems())
        .extracting(KoreanItemExtractor.ExtractedItem::action)
        .containsExactly("읽기", "요약", "만들기");
    assertThat(result.detectedItems())
        .extracting(KoreanItemExtractor.ExtractedItem::object)
        .containsExactly("책", "핵심", "도표");
    assertThat(result.detectedItems())
        .extracting(item -> source(content, item))
        .containsExactly("책 읽고", "핵심 요약하고", "도표 만들기");
    assertThat(result.signals()).containsExactly(AmbiguityReason.MULTI_INTENT);
  }

  @Test
  void keepsAllFourSemanticItemsAndReportsReviewOverflowWithoutTruncating() {
    String content = "책 읽고 핵심 요약하고 도표 만들기, 발표 준비하기";

    var result = extract(content);

    assertThat(result.detectedItems()).hasSize(4);
    assertThat(result.allItems()).isEqualTo(result.detectedItems());
    assertThat(result.detectedItems())
        .extracting(item -> source(content, item))
        .containsExactly("책 읽고", "핵심 요약하고", "도표 만들기", "발표 준비하기");
    assertThat(result.signals())
        .containsExactlyInAnyOrder(
            AmbiguityReason.MULTI_INTENT, AmbiguityReason.CANDIDATE_LIMIT_EXCEEDED);
  }

  @Test
  void keepsANextConnectorAsEvidenceAndPropagatesAnUnresolvedObjectSafely() {
    String content = "선배가 말한 자료 찾아보고 핵심 정리한 다음 깃에 올리기";

    var result = extract(content);

    assertThat(result.detectedItems())
        .extracting(item -> source(content, item))
        .containsExactly("선배가 말한 자료 찾아보고", "핵심 정리한 다음", "깃에 올리기");
    assertThat(result.detectedItems().getLast().object()).isNull();
    assertThat(result.signals())
        .containsExactlyInAnyOrder(
            AmbiguityReason.UNRESOLVED_REFERENCE, AmbiguityReason.MULTI_INTENT);
  }

  @Test
  void doesNotEmitAlternativeBranchesAsSimultaneousItems() {
    String content = "계약서 검토 또는 수정 방향 결정";

    var result = extract(content);

    assertThat(result.alternative()).isTrue();
    assertThat(result.detectedItems()).hasSize(1);
    assertThat(result.detectedItemCount()).isEqualTo(2);
    assertThat(result.detectedItems().getFirst().title()).isEqualTo("계약서 검토");
    assertThat(source(content, result.detectedItems().getFirst())).isEqualTo("계약서 검토");
    assertThat(result.signals()).contains(AmbiguityReason.MULTI_INTENT);
  }

  @Test
  void stillReportsOverflowWhenAnAlternativeKeepsOnlyOneReviewCandidate() {
    String content = "책 읽고 핵심 요약하고 도표 만들고 발표 준비하기 또는 쉬기";

    var result = extract(content);

    assertThat(result.alternative()).isTrue();
    assertThat(result.detectedItems()).hasSize(1);
    assertThat(result.detectedItemCount()).isEqualTo(4);
    assertThat(result.signals())
        .containsExactlyInAnyOrder(
            AmbiguityReason.MULTI_INTENT, AmbiguityReason.CANDIDATE_LIMIT_EXCEEDED);
  }

  @Test
  void routesAnActionlessAlternativeToReviewWithoutFlatteningBothEvents() {
    String content = "10월 3일 회의 또는 동창회";

    var result = extract(content);

    assertThat(result.alternative()).isTrue();
    assertThat(result.detectedItemCount()).isEqualTo(2);
    assertThat(result.detectedItems()).hasSize(1);
    assertThat(result.detectedItems().getFirst().kind()).isEqualTo("EVENT");
    assertThat(result.signals()).containsExactly(AmbiguityReason.MULTI_INTENT);
  }

  @Test
  void treatsNearDemonstrativesAsUnresolvedWithoutMatchingEmailWords() {
    var unresolved = extract("이 문서 올리기");
    var email = extract("이메일 주소 확인하기");

    assertThat(unresolved.detectedItems().getFirst().object()).isNull();
    assertThat(unresolved.signals()).contains(AmbiguityReason.UNRESOLVED_REFERENCE);
    assertThat(email.detectedItems().getFirst().object()).isEqualTo("이메일 주소");
    assertThat(email.signals()).doesNotContain(AmbiguityReason.UNRESOLVED_REFERENCE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "이 문서부터 올리기",
        "이 문서까지 확인하기",
        "그것으로 정리하기",
        "이 문서로 정리하기",
        "이 문서랑 정리하기",
        "그것이라도 확인하기",
        "이 문서에서부터 확인하기",
        "그것만이라도 확인하기",
        "이 문서에서부터라도 확인하기"
      })
  void keepsDemonstrativesWithCommonParticlesUnresolved(String content) {
    var result = extract(content);

    assertThat(result.detectedItems().getFirst().object()).isNull();
    assertThat(result.signals()).contains(AmbiguityReason.UNRESOLVED_REFERENCE);
  }

  @Test
  void leavesAnUnresolvedReferenceObjectNullAndSignalsIt() {
    String content = "선배가 말한 그 파일 다시 보기";

    var result = extract(content);

    assertThat(result.detectedItems())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.action()).isEqualTo("다시 보기");
              assertThat(item.object()).isNull();
              assertThat(source(content, item)).isEqualTo(content);
            });
    assertThat(result.signals())
        .contains(AmbiguityReason.UNRESOLVED_REFERENCE)
        .doesNotContain(AmbiguityReason.MISSING_OBJECT);
  }

  @Test
  void treatsAReferencedDocumentWithoutAnIdentityAsUnresolved() {
    String content = "팀장이 전에 말한 문서 확인하기";

    var result = extract(content);

    assertThat(result.detectedItems().getFirst().object()).isNull();
    assertThat(result.signals())
        .contains(AmbiguityReason.UNRESOLVED_REFERENCE)
        .doesNotContain(AmbiguityReason.MISSING_OBJECT);
  }

  @Test
  void keepsAnImplicitSecondObjectUnresolvedInsteadOfInventingOne() {
    String content = "논문 읽고 요약하기";

    var result = extract(content);

    assertThat(result.detectedItems()).hasSize(2);
    assertThat(result.detectedItems().get(0).object()).isEqualTo("논문");
    assertThat(result.detectedItems().get(1).object()).isNull();
    assertThat(source(content, result.detectedItems().get(1))).isEqualTo("요약하기");
    assertThat(result.signals())
        .containsExactlyInAnyOrder(AmbiguityReason.MISSING_OBJECT, AmbiguityReason.MULTI_INTENT);
  }

  @Test
  void neverUsesWhitespaceCompactionOrUnicodeCodePointsAsSourceOffsets() {
    String content = "😀  책\t읽고\n요약하기";

    var result = extract(content);

    assertThat(result.detectedItems()).hasSize(2);
    var second = result.detectedItems().get(1);
    assertThat(second.startOffset()).isEqualTo(content.indexOf("요약하기"));
    assertThat(second.endOffset()).isEqualTo(content.length());
    assertThat(source(content, result.detectedItems().get(0))).isEqualTo("😀  책\t읽고");
    assertThat(source(content, second)).isEqualTo("요약하기");
  }

  @ParameterizedTest
  @ValueSource(strings = {"읽고 싶은 책", "확인하기 좋은 체크리스트", "제출하고 싶은 양식"})
  void avoidsActionFormsUsedAsNonCommandModifiers(String content) {
    var result = extract(content);

    assertThat(result.detectedItems())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.kind()).isEqualTo("RECORD");
              assertThat(item.action()).isNull();
              assertThat(source(content, item)).isEqualTo(content);
            });
    assertThat(result.signals()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"책 읽기", "체크리스트 확인하기", "양식 제출하기"})
  void keepsAffirmativeActionsAfterTheModifierGuard(String content) {
    var result = extract(content);

    assertThat(result.detectedItems())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.kind()).isEqualTo("TASK");
              assertThat(item.action()).isNotNull();
              assertThat(source(content, item)).isEqualTo(content);
            });
  }

  @Test
  void classifiesMinimalInformationEventAndRecordShapesWithoutSpecialSentences() {
    String information = "API 응답 캐시는 사용자별로 분리해야 함";
    String event = "10월 3일 동창회";
    String record = "운동 기록 스쿼트 80kg 5세트";

    var informationResult = extract(information).detectedItems().getFirst();
    var eventResult = extract(event).detectedItems().getFirst();
    var recordResult = extract(record).detectedItems().getFirst();

    assertThat(informationResult.kind()).isEqualTo("INFORMATION");
    assertThat(informationResult.object()).isEqualTo("API 응답 캐시");
    assertThat(source(information, informationResult)).isEqualTo(information);
    assertThat(eventResult.kind()).isEqualTo("EVENT");
    assertThat(eventResult.title()).isEqualTo("동창회");
    assertThat(source(event, eventResult)).isEqualTo("동창회");
    assertThat(recordResult.kind()).isEqualTo("RECORD");
    assertThat(recordResult.object()).isEqualTo("스쿼트 80kg 5세트");
    assertThat(source(record, recordResult)).isEqualTo(record);
  }

  @Test
  void usesTheMatchedEventNounAsTheSourceAlignedItemForACalendarEvent() {
    String content = "회의는 다음 주 수요일 오후 2시";

    var result = extract(content);

    assertThat(result.detectedItems())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.kind()).isEqualTo("EVENT");
              assertThat(item.title()).isEqualTo("회의");
              assertThat(item.object()).isEqualTo("회의");
              assertThat(source(content, item)).isEqualTo("회의");
            });
  }

  @Test
  void normalizesAPastEventWhileKeepingItsWholeRawSpan() {
    String content = "어제 운영체제 중간고사 봤음";

    var result = extract(content);

    assertThat(result.detectedItems())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.kind()).isEqualTo("EVENT");
              assertThat(item.title()).isEqualTo("운영체제 중간고사를 봄");
              assertThat(item.object()).isEqualTo("운영체제 중간고사");
              assertThat(source(content, item)).isEqualTo(content);
            });
  }

  @Test
  void preservesFourSequentialActionsAndFailsClosedForADestinationOnlyObject() {
    String content = "자료 찾아보고 중요한 부분 정리한 다음 깃에 올리고 시간 되면 발표 준비도 하기";

    var result = extract(content);

    assertThat(result.detectedItems()).hasSize(4);
    assertThat(result.detectedItems())
        .extracting(KoreanItemExtractor.ExtractedItem::action)
        .containsExactly("찾아보기", "정리", "올리기", "준비");
    assertThat(result.detectedItems())
        .extracting(KoreanItemExtractor.ExtractedItem::object)
        .containsExactly("자료", "중요한 부분", null, "발표");
    assertThat(source(content, result.detectedItems().get(2))).isEqualTo("깃에 올리고");
    assertThat(source(content, result.detectedItems().get(3))).isEqualTo("시간 되면 발표 준비도 하기");
    assertThat(result.signals())
        .containsExactlyInAnyOrder(
            AmbiguityReason.MISSING_OBJECT,
            AmbiguityReason.MULTI_INTENT,
            AmbiguityReason.CANDIDATE_LIMIT_EXCEEDED);
  }

  @Test
  void removesParsedDatesFromATaskTitleWithoutChangingItsRawSpan() {
    String content = "과제는 25일까지 제출";

    var result = extract(content);

    assertThat(result.detectedItems())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.title()).isEqualTo("과제는 제출");
              assertThat(item.object()).isEqualTo("과제");
              assertThat(source(content, item)).isEqualTo(content);
            });
  }

  @Test
  void extractsADeclarativePrefixAndFollowingTaskWithoutAFixturePhraseBranch() {
    String content = "캐시 정책은 중요하고 보고서는 9월 18일까지 제출";

    var result = extract(content);

    assertThat(result.detectedItems()).hasSize(2);
    assertThat(result.detectedItems())
        .extracting(KoreanItemExtractor.ExtractedItem::kind)
        .containsExactly("INFORMATION", "TASK");
    assertThat(result.detectedItems().get(0).title()).isEqualTo("캐시 정책은 중요");
    assertThat(result.detectedItems().get(0).object()).isEqualTo("캐시 정책");
    assertThat(result.detectedItems().get(1).action()).isEqualTo("제출");
    assertThat(result.detectedItems().get(1).object()).isEqualTo("보고서");
    assertThat(source(content, result.detectedItems().get(0))).isEqualTo("캐시 정책은 중요");
    assertThat(source(content, result.detectedItems().get(1))).isEqualTo("보고서는 9월 18일까지 제출");
    assertThat(result.signals()).contains(AmbiguityReason.MULTI_INTENT);
  }

  private KoreanItemExtractor.Extraction extract(String content) {
    return extractor.extract(content, dates.parse(content, BASE, SEOUL));
  }

  private String source(String content, KoreanItemExtractor.ExtractedItem item) {
    return content.substring(item.startOffset(), item.endOffset());
  }
}
