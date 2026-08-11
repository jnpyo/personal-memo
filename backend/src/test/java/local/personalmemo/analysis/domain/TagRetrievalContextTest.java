package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import local.personalmemo.analysis.domain.TagRetrievalContext.Candidate;
import local.personalmemo.analysis.domain.TagRetrievalContext.MatchKind;
import org.junit.jupiter.api.Test;

class TagRetrievalContextTest {
  private static final UUID FIRST_TAG_ID = UUID.fromString("10000000-0000-0000-0000-000000000016");

  @Test
  void acceptsTheEmptyAndMaximumBoundAndDefensivelyCopiesCandidates() {
    assertThat(
            new TagRetrievalContext(TagRetrievalContext.CURRENT_VERSION, List.of())
                .candidateCount())
        .isZero();

    List<Candidate> candidates = new ArrayList<>();
    for (int index = 0; index < TagRetrievalContext.MAX_CANDIDATES; index++) {
      candidates.add(candidate(index + 1, new UUID(0L, index + 1L), index));
    }
    TagRetrievalContext context =
        new TagRetrievalContext(TagRetrievalContext.CURRENT_VERSION, candidates);
    candidates.clear();

    assertThat(context.candidates()).hasSize(TagRetrievalContext.MAX_CANDIDATES);
    assertThatThrownBy(() -> context.candidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsUnsupportedVersionsTooManyCandidatesAndNonSequentialRanks() {
    assertThatThrownBy(() -> new TagRetrievalContext("none", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TagRetrievalContext(null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TagRetrievalContext(TagRetrievalContext.CURRENT_VERSION, null))
        .isInstanceOf(NullPointerException.class);

    List<Candidate> tooMany = new ArrayList<>();
    for (int index = 0; index <= TagRetrievalContext.MAX_CANDIDATES; index++) {
      int rank = Math.min(index + 1, TagRetrievalContext.MAX_CANDIDATES);
      tooMany.add(candidate(rank, new UUID(0L, index + 1L), index));
    }
    assertThatThrownBy(() -> new TagRetrievalContext(TagRetrievalContext.CURRENT_VERSION, tooMany))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new TagRetrievalContext(
                    TagRetrievalContext.CURRENT_VERSION, List.of(candidate(2, FIRST_TAG_ID, 0))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequential");
  }

  @Test
  void rejectsDuplicateTagIdsEvenWhenTheMatchDetailsDiffer() {
    Candidate canonical = candidate(1, FIRST_TAG_ID, 0);
    Candidate alias = new Candidate(2, FIRST_TAG_ID, "프로젝트", "업무", MatchKind.ALIAS, 1);

    assertThatThrownBy(
            () ->
                new TagRetrievalContext(
                    TagRetrievalContext.CURRENT_VERSION, List.of(canonical, alias)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
  }

  @Test
  void enforcesCandidateRankNameAliasAndSourceBoundaries() {
    assertThatCode(
            () ->
                new Candidate(
                    1,
                    FIRST_TAG_ID,
                    "😀".repeat(100),
                    null,
                    MatchKind.CANONICAL,
                    TagRetrievalContext.MAX_SOURCE_CANDIDATE_INDEX))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> new Candidate(0, FIRST_TAG_ID, "태그", null, MatchKind.CANONICAL, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Candidate(9, FIRST_TAG_ID, "태그", null, MatchKind.CANONICAL, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Candidate(1, FIRST_TAG_ID, "😀".repeat(101), null, MatchKind.CANONICAL, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Candidate(1, FIRST_TAG_ID, " ", null, MatchKind.CANONICAL, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Candidate(1, FIRST_TAG_ID, "태그", "별칭", MatchKind.CANONICAL, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Candidate(1, FIRST_TAG_ID, "태그", null, MatchKind.ALIAS, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Candidate(1, FIRST_TAG_ID, "태그", " ", MatchKind.ALIAS, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Candidate(1, FIRST_TAG_ID, "태그", null, MatchKind.CANONICAL, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Candidate(
                    1,
                    FIRST_TAG_ID,
                    "태그",
                    null,
                    MatchKind.CANONICAL,
                    TagRetrievalContext.MAX_SOURCE_CANDIDATE_INDEX + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void redactsCandidateContentAndIdentifiersFromStringForms() {
    Candidate candidate = new Candidate(1, FIRST_TAG_ID, "비밀 프로젝트", "극비", MatchKind.ALIAS, 0);
    TagRetrievalContext context =
        new TagRetrievalContext(TagRetrievalContext.CURRENT_VERSION, List.of(candidate));

    assertThat(context.toString())
        .contains("candidateCount=1", "candidates=redacted")
        .doesNotContain(FIRST_TAG_ID.toString(), "비밀 프로젝트", "극비");
    assertThat(candidate.toString())
        .contains("redacted")
        .doesNotContain(FIRST_TAG_ID.toString(), "비밀 프로젝트", "극비");
  }

  private Candidate candidate(int rank, UUID id, int sourceCandidateIndex) {
    return new Candidate(rank, id, "태그 " + rank, null, MatchKind.CANONICAL, sourceCandidateIndex);
  }
}
