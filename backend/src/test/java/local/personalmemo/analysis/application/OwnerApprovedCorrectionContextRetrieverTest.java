package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import local.personalmemo.analysis.application.ApprovedCorrectionCandidateRepository.CandidateRow;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.Classification;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.CorrectedFields;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.Outcome;
import local.personalmemo.analysis.domain.ApprovedCorrectionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwnerApprovedCorrectionContextRetrieverTest {
  private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID TARGET_MEMO_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000001");

  private ApprovedCorrectionCandidateRepository repository;
  private AnalysisReviewOutcomeClassifier classifier;
  private OwnerApprovedCorrectionContextRetriever retriever;

  @BeforeEach
  void setUp() {
    repository = mock(ApprovedCorrectionCandidateRepository.class);
    classifier = mock(AnalysisReviewOutcomeClassifier.class);
    retriever = new OwnerApprovedCorrectionContextRetriever(repository, classifier);
    when(classifier.classify(any(), any(), any())).thenReturn(correctedType());
  }

  @Test
  void buildsOnlyAnOffsetAndApprovedKindThenRehydratesFromTheTarget() {
    String target = "😀 6시 디스코드 접속하기";
    when(repository.findLatestCurrentApplied(OWNER_ID, TARGET_MEMO_ID))
        .thenReturn(List.of(candidate(1, "7시 팀 채팅 접속하기", "TASK")));

    ApprovedCorrectionContext context = retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, target);

    int start = target.indexOf("접속하기");
    assertThat(context.version()).isEqualTo("approved-type-anchor-k3-v1");
    assertThat(context.signals())
        .containsExactly(
            new ApprovedCorrectionContext.Signal(start, start + "접속하기".length(), "TASK"));
    assertThat(context.rehydrate(target))
        .containsExactly(new ApprovedCorrectionContext.Hint("접속하기", "TASK"));
    assertThat(context.toString())
        .doesNotContain("접속하기", OWNER_ID.toString(), TARGET_MEMO_ID.toString());
    verify(repository).findLatestCurrentApplied(OWNER_ID, TARGET_MEMO_ID);
  }

  @Test
  void acceptsUserResolutionButRejectsExactAndNonTypeCorrection() {
    when(repository.findLatestCurrentApplied(OWNER_ID, TARGET_MEMO_ID))
        .thenReturn(List.of(candidate(1, "접속하기", "EVENT")));
    when(classifier.classify(any(), any(), any()))
        .thenReturn(new Classification(Outcome.USER_RESOLVED, noCorrections()));

    assertThat(retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, "접속하기").signals())
        .extracting(ApprovedCorrectionContext.Signal::approvedKind)
        .containsExactly("EVENT");

    when(classifier.classify(any(), any(), any()))
        .thenReturn(new Classification(Outcome.EXACT, noCorrections()))
        .thenReturn(
            new Classification(
                Outcome.CORRECTED, new CorrectedFields(false, true, false, false, false)));
    assertThat(retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, "접속하기").signals()).isEmpty();
    assertThat(retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, "접속하기").signals()).isEmpty();
  }

  @Test
  void forwardsSchemaV3RowsToClassifierEligibility() {
    when(repository.findLatestCurrentApplied(OWNER_ID, TARGET_MEMO_ID))
        .thenReturn(List.of(candidate(1, "접속하기", "TASK", null, "3")));

    assertThat(retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, "접속하기").signals())
        .extracting(ApprovedCorrectionContext.Signal::approvedKind)
        .containsExactly("TASK");
  }

  @Test
  void rejectsUnclassifiableSchemaV3Rows() {
    when(repository.findLatestCurrentApplied(OWNER_ID, TARGET_MEMO_ID))
        .thenReturn(List.of(candidate(1, "접속하기", "EVENT", null, "3")));
    when(classifier.classify(any(), any(), any()))
        .thenReturn(new Classification(Outcome.UNCLASSIFIABLE, noCorrections()));

    assertThat(retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, "접속하기").signals()).isEmpty();
  }

  @Test
  void removesAnAnchorWhenEligibleApprovalsConflict() {
    when(repository.findLatestCurrentApplied(OWNER_ID, TARGET_MEMO_ID))
        .thenReturn(List.of(candidate(1, "접속하기", "TASK"), candidate(2, "접속하기", "EVENT")));

    assertThat(retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, "접속하기").signals()).isEmpty();
  }

  @Test
  void rejectsInactiveUndoneStaleRejectedMultiItemAndRelationRows() {
    List<CandidateRow> invalid = new ArrayList<>();
    int seed = 1;
    for (Fault fault : Fault.values()) {
      invalid.add(candidateWithFault(seed++, fault));
    }
    when(repository.findLatestCurrentApplied(OWNER_ID, TARGET_MEMO_ID)).thenReturn(invalid);

    assertThat(retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, "접속하기").signals()).isEmpty();
  }

  @Test
  void capsTheDefensiveScanAndTheOutputAtThreeSignals() {
    List<CandidateRow> overScanCap = new ArrayList<>();
    for (int index = 0;
        index < ApprovedCorrectionCandidateRepository.MAX_SCAN_CANDIDATES;
        index++) {
      overScanCap.add(candidate(index + 1, "다른문장", "TASK"));
    }
    overScanCap.add(candidate(100, "접속하기", "TASK"));
    when(repository.findLatestCurrentApplied(OWNER_ID, TARGET_MEMO_ID)).thenReturn(overScanCap);

    assertThat(retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, "접속하기").signals()).isEmpty();

    when(repository.findLatestCurrentApplied(OWNER_ID, TARGET_MEMO_ID))
        .thenReturn(
            List.of(
                candidate(1, "접속하기", "TASK"),
                candidate(2, "기록하기", "TASK"),
                candidate(3, "공유하기", "TASK"),
                candidate(4, "확인하기", "TASK")));

    ApprovedCorrectionContext context =
        retriever.retrieve(OWNER_ID, TARGET_MEMO_ID, "접속하기 기록하기 공유하기 확인하기");
    assertThat(context.signals()).hasSize(ApprovedCorrectionContext.MAX_SIGNALS);
    assertThat(context.rehydrate("접속하기 기록하기 공유하기 확인하기"))
        .extracting(ApprovedCorrectionContext.Hint::anchorText)
        .containsExactly("확인하기", "공유하기", "기록하기");
  }

  private CandidateRow candidate(int seed, String sourceContent, String approvedKind) {
    return candidate(seed, sourceContent, approvedKind, null);
  }

  private CandidateRow candidateWithFault(int seed, Fault fault) {
    return candidate(seed, "접속하기", "TASK", fault);
  }

  private CandidateRow candidate(int seed, String sourceContent, String approvedKind, Fault fault) {
    return candidate(seed, sourceContent, approvedKind, fault, "2");
  }

  private CandidateRow candidate(
      int seed, String sourceContent, String approvedKind, Fault fault, String schemaVersion) {
    UUID sourceMemoId = uuid(3000 + seed);
    UUID applicationMemoId = sourceMemoId;
    UUID runMemoId = sourceMemoId;
    int sourceRevision = 1;
    int applicationRevision = 1;
    int runRevision = 1;
    String memoStatus = "ACTIVE";
    String applicationStatus = "APPLIED";
    String runStatus = "APPLIED";
    String runSchemaVersion = schemaVersion;
    String proposalJson = "{\"itemCandidates\":[{}],\"relationCandidates\":[]}";
    String selectionJson = "{\"selectedType\":\"" + approvedKind + "\",\"items\":[{}]}";

    if (fault != null) {
      switch (fault) {
        case TARGET_MEMO -> {
          sourceMemoId = TARGET_MEMO_ID;
          applicationMemoId = TARGET_MEMO_ID;
          runMemoId = TARGET_MEMO_ID;
        }
        case INACTIVE -> memoStatus = "ARCHIVED";
        case UNDONE -> applicationStatus = "UNDONE";
        case RUN_NOT_APPLIED -> runStatus = "SUCCEEDED";
        case SOURCE_STALE -> sourceRevision = 2;
        case RUN_STALE -> runRevision = 2;
        case UNSUPPORTED_SCHEMA -> runSchemaVersion = "4";
        case MULTI_ITEM -> proposalJson = "{\"itemCandidates\":[{},{}],\"relationCandidates\":[]}";
        case MULTI_SELECTION -> selectionJson = "{\"selectedType\":\"TASK\",\"items\":[{},{}]}";
        case RELATION -> proposalJson = "{\"itemCandidates\":[{}],\"relationCandidates\":[{}]}";
        case SELECTED_RELATION ->
            selectionJson = "{\"selectedType\":\"TASK\",\"items\":[{}],\"selectedRelations\":[{}]}";
      }
    }

    return new CandidateRow(
        uuid(4000 + seed),
        applicationStatus,
        applicationMemoId,
        applicationRevision,
        Instant.parse("2026-08-23T00:00:00Z").plus(seed, ChronoUnit.SECONDS),
        sourceMemoId,
        sourceRevision,
        memoStatus,
        sourceContent,
        runMemoId,
        runRevision,
        runStatus,
        runSchemaVersion,
        proposalJson,
        selectionJson);
  }

  private UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
  }

  private Classification correctedType() {
    return new Classification(
        Outcome.CORRECTED, new CorrectedFields(true, false, false, false, false));
  }

  private CorrectedFields noCorrections() {
    return new CorrectedFields(false, false, false, false, false);
  }

  private enum Fault {
    TARGET_MEMO,
    INACTIVE,
    UNDONE,
    RUN_NOT_APPLIED,
    SOURCE_STALE,
    RUN_STALE,
    UNSUPPORTED_SCHEMA,
    MULTI_ITEM,
    MULTI_SELECTION,
    RELATION,
    SELECTED_RELATION
  }
}
