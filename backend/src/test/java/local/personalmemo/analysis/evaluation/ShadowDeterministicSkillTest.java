package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.infrastructure.Draft202012AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ShadowDeterministicSkillTest {
  private final ObjectMapper json = new ObjectMapper();
  private final FakeAnalyzer fake = new FakeAnalyzer(json);
  private final DeterministicAmbiguityGate ambiguityGate = new DeterministicAmbiguityGate();
  private final ShadowDeterministicSkill skill = new ShadowDeterministicSkill(json);

  @Test
  void projectsOnlyBoundedItemsFromAValidatedAuthoritativeProposal() {
    UUID memoId = UUID.fromString("00000000-0000-0000-0000-000000000106");
    String memo = "보고서를 작성하고 제출하기";
    ObjectNode authoritative = analyze(memoId, memo);

    SkillProjection projection = project(authoritative, memoId, memo);

    assertThat(skill.skillOnlyProposal(projection)).isEqualTo(authoritative);
    assertThat(projection.evidenceJson().propertyNames())
        .containsExactlyInAnyOrder("schemaVersion", "defaultTitle", "items");
    assertThat(projection.evidenceJson().findValue("memoId")).isNull();
    assertThat(projection.evidenceJson().findValue("content")).isNull();
    assertThat(projection.evidenceJson().findValue("expectedTypes")).isNull();
    assertThat(projection.evidenceJson().findValue("notes")).isNull();
    assertThat(projection.evidence().items()).hasSizeBetween(1, 3);
  }

  @Test
  void acceptedSelectionCanChangeOnlySuggestedTitleValueAndTopicsRemainDiagnostic() {
    UUID memoId = UUID.fromString("00000000-0000-0000-0000-000000000107");
    String memo = "발표 자료를 작성하고 검토하기";
    ObjectNode authoritative = analyze(memoId, memo);
    SkillProjection projection = project(authoritative, memoId, memo);
    int primary = projection.evidence().items().size() > 1 ? 1 : 0;
    int groundedTopic = firstGroundedTopic(projection.evidence());
    List<Integer> topics = groundedTopic < 0 ? List.of() : List.of(groundedTopic);
    ObjectNode selection = selection(primary, topics);

    SkillSelection validated = skill.validateSelection(selection, projection.evidence());
    ObjectNode guarded = skill.guardedProposal(projection, validated);

    ShadowDeterministicSkill.assertOnlySuggestedTitleValueChanged(authoritative, guarded);
    assertThat(guarded.at("/suggestedTitle/value").asText())
        .isEqualTo(projection.evidence().items().get(primary).title());
    assertThat(guarded.path("tagCandidates")).isEqualTo(authoritative.path("tagCandidates"));
    assertThat(guarded.path("ambiguityReasons")).isEqualTo(authoritative.path("ambiguityReasons"));
    assertThat(guarded.path("providerMetadata")).isEqualTo(authoritative.path("providerMetadata"));
    new Draft202012AnalysisProposalSchemaValidator().validate(guarded);
    new AnalysisProposalValidator()
        .validate(guarded, memoId, 1, memo, fake.provenance(), ambiguityGate.version());
  }

  @Test
  void rejectsTheWholeSelectionWithoutClampRepairOrPartialTopicAcceptance() {
    SkillEvidence evidence =
        new SkillEvidence(
            "기본 제목",
            List.of(
                new SkillEvidenceItem(0, "TASK", "첫 제목", "같은 대상"),
                new SkillEvidenceItem(1, "TASK", "둘째 제목", "같은 대상"),
                new SkillEvidenceItem(2, "RECORD", "셋째 제목", null)));

    assertRejected(selection(3, List.of()), evidence, SkillSelectionRejection.PRIMARY_OUT_OF_RANGE);
    assertRejected(selection(0, List.of(2)), evidence, SkillSelectionRejection.TOPIC_NOT_GROUNDED);
    assertRejected(selection(0, List.of(0, 1)), evidence, SkillSelectionRejection.TOPIC_DUPLICATE);
    assertRejected(
        selection(-1, List.of()), evidence, SkillSelectionRejection.PRIMARY_OUT_OF_RANGE);
  }

  @Test
  void requiresMinusOneExactlyWhenTheAuthoritativeProposalHasNoItems() {
    SkillEvidence empty = new SkillEvidence("기본 제목", List.of());

    assertThat(skill.validateSelection(selection(-1, List.of()), empty).primaryItemOrdinal())
        .isEqualTo(-1);
    assertRejected(
        selection(0, List.of()), empty, SkillSelectionRejection.PRIMARY_EMPTY_CONTRADICTION);
  }

  @Test
  void protectedMutationGuardRejectsAnyChangeOutsideTheAllowedTitlePointer() {
    UUID memoId = UUID.fromString("00000000-0000-0000-0000-000000000108");
    String memo = "공개 합성 기록";
    ObjectNode authoritative = analyze(memoId, memo);
    ObjectNode mutated = authoritative.deepCopy();
    mutated.withArray("ambiguityReasons").add("MISSING_OBJECT");

    assertThatThrownBy(
            () ->
                ShadowDeterministicSkill.assertOnlySuggestedTitleValueChanged(
                    authoritative, mutated))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("model selection changed a protected proposal field");
  }

  @Test
  void controlledExistingItemSelectionCanImproveOnlyTheTitlePointer() {
    UUID memoId = UUID.fromString("00000000-0000-0000-0000-000000000109");
    String memo = "보고서 제출하기";
    ObjectNode authoritative = analyze(memoId, memo);
    authoritative.withObject("suggestedTitle").put("value", "의도적으로 덜 정확한 공개 합성 제목");
    SkillProjection projection = project(authoritative, memoId, memo);
    ObjectNode selection = selection(0, List.of());

    ObjectNode guarded =
        skill.guardedProposal(
            projection, skill.validateSelection(selection, projection.evidence()));

    assertThat(guarded.at("/suggestedTitle/value").asText())
        .isEqualTo(projection.evidence().items().getFirst().title())
        .isNotEqualTo(authoritative.at("/suggestedTitle/value").asText());
    ShadowDeterministicSkill.assertOnlySuggestedTitleValueChanged(authoritative, guarded);
  }

  private ObjectNode analyze(UUID memoId, String memo) {
    return fake.analyze(memoId, 1, memo, Instant.parse("2030-01-02T03:04:05Z"), "Asia/Seoul");
  }

  private SkillProjection project(ObjectNode proposal, UUID memoId, String memo) {
    return skill.project(proposal, memoId, 1, memo, fake.provenance(), ambiguityGate.version());
  }

  private ObjectNode selection(int primary, List<Integer> topics) {
    ObjectNode value =
        json.createObjectNode()
            .put("schemaVersion", ShadowDeterministicSkill.SELECTION_VERSION)
            .put("primaryItemOrdinal", primary);
    var topicValues = value.putArray("topicObjectOrdinals");
    for (int topic : topics) {
      topicValues.add(topic);
    }
    return value;
  }

  private int firstGroundedTopic(SkillEvidence evidence) {
    return evidence.items().stream()
        .filter(
            item ->
                item.objectValue() != null
                    && item.objectValue().codePointCount(0, item.objectValue().length()) <= 100)
        .mapToInt(SkillEvidenceItem::ordinal)
        .findFirst()
        .orElse(-1);
  }

  private void assertRejected(
      ObjectNode selection, SkillEvidence evidence, SkillSelectionRejection expectedReason) {
    assertThatThrownBy(() -> skill.validateSelection(selection, evidence))
        .isInstanceOfSatisfying(
            SkillSelectionRejectedException.class,
            exception -> {
              assertThat(exception.reason()).isEqualTo(expectedReason);
              assertThat(exception.getMessage()).isEqualTo("Bounded model selection was rejected.");
              assertThat(exception.getMessage()).doesNotContain(selection.toString());
            });
  }
}
