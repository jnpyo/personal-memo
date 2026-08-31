package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.Outcome;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.ReviewContext;
import local.personalmemo.analysis.infrastructure.Draft202012AnalysisProposalSchemaValidator;
import local.personalmemo.taxonomy.domain.TagNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class AnalysisReviewOutcomeClassifierTest {
  private static final String TAG_ID = "10000000-0000-0000-0000-000000000001";
  private static final UUID MEMO_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

  private final ObjectMapper json = new ObjectMapper();
  private final AnalysisReviewOutcomeClassifier classifier =
      new AnalysisReviewOutcomeClassifier(
          new TagNormalizer(), new Draft202012AnalysisProposalSchemaValidator());
  private ObjectNode proposal;
  private ObjectNode selection;
  private ReviewContext context;

  @BeforeEach
  void setUp() {
    proposal = taskProposal();
    selection = exactSelection();
    context = reviewContext();
  }

  @Test
  void exactIgnoresTagOrderAndComparesDatesSemantically() {
    ObjectNode exactDate = (ObjectNode) proposal.path("dateCandidates").get(0);
    exactDate
        .put("surfaceText", "2026.11.25 18:00")
        .put("value", "2026-11-25T18:00:00+09:00")
        .put("precision", "EXACT_TIME")
        .put("timeSpecified", true);
    ObjectNode storedDue = (ObjectNode) selection.at("/items/0/due");
    storedDue
        .put("surfaceText", "2026.11.25 18:00")
        .put("originalValue", "2026-11-25T09:00:00Z")
        .put("precision", "EXACT_TIME")
        .put("timeSpecified", true)
        .put("dueInstant", "2026-11-25T09:00:00Z")
        .putNull("dueLocalDate");

    var result = classifier.classify(proposal, selection, context);

    assertThat(result.outcome()).isEqualTo(Outcome.EXACT);
    assertThat(result.correctedFields().any()).isFalse();
  }

  @Test
  void exactDoesNotGuessADueWhenMultipleDatesNeedUserMapping() {
    proposal
        .withArray("dateCandidates")
        .addObject()
        .put("surfaceText", "11.30")
        .put("value", "2026-11-30")
        .put("precision", "DATE_ONLY")
        .put("timeSpecified", false)
        .put("confidence", 0.7)
        .putArray("ambiguityReasons");
    ((ObjectNode) selection.path("items").get(0)).putNull("due");

    var result = classifier.classify(proposal, selection, context);

    assertThat(result.outcome()).isEqualTo(Outcome.EXACT);
    assertThat(result.correctedFields().due()).isFalse();
  }

  @Test
  void exactProjectsThePreferredTaskBeforeAssigningTheOnlyPreciseDue() {
    ((ObjectNode) proposal.path("itemCandidates").get(0)).put("kind", "INFORMATION");

    var result = classifier.classify(proposal, selection, context);

    assertThat(result.outcome()).isEqualTo(Outcome.EXACT);
    assertThat(result.correctedFields().any()).isFalse();
  }

  @Test
  void versionTwoUsesTheExplicitBindingForExactAndCorrectedDueOutcomes() {
    proposal = versionTwoTaskProposal();
    context = versionTwoReviewContext();

    var exact = classifier.classify(proposal, selection, context);

    assertThat(exact.outcome()).isEqualTo(Outcome.EXACT);
    assertThat(exact.correctedFields().any()).isFalse();

    ((ObjectNode) selection.path("items").get(0)).putNull("due");
    var corrected = classifier.classify(proposal, selection, context);

    assertThat(corrected.outcome()).isEqualTo(Outcome.CORRECTED);
    assertThat(corrected.correctedFields().due()).isTrue();
    assertThat(corrected.correctedFields().type()).isFalse();
    assertThat(corrected.correctedFields().title()).isFalse();
    assertThat(corrected.correctedFields().tags()).isFalse();
    assertThat(corrected.correctedFields().items()).isFalse();
  }

  @Test
  void versionThreeTaskOnlyProposalUsesTheExplicitDueBinding() {
    proposal = versionThreeTaskProposal();
    context = new ReviewContext("3", MEMO_ID, 1, MEMO_ID, 1);

    var exact = classifier.classify(proposal, selection, context);

    assertThat(exact.outcome()).isEqualTo(Outcome.EXACT);
    assertThat(exact.correctedFields().any()).isFalse();

    ((ObjectNode) selection.path("items").get(0)).putNull("due");
    var corrected = classifier.classify(proposal, selection, context);

    assertThat(corrected.outcome()).isEqualTo(Outcome.CORRECTED);
    assertThat(corrected.correctedFields().due()).isTrue();
  }

  @Test
  void versionTwoUnboundPreciseAndImpreciseDatesRequireUserResolution() {
    proposal = versionTwoTaskProposal();
    context = versionTwoReviewContext();
    ((ObjectNode) proposal.path("itemCandidates").get(0)).putNull("dueDateCandidateId");
    ((ObjectNode) selection.path("items").get(0)).putNull("due");

    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.USER_RESOLVED);

    proposal = versionTwoTaskProposal();
    proposal
        .putArray("dateCandidates")
        .addObject()
        .put("candidateId", "date-approximate")
        .put("surfaceText", "around the weekend")
        .putNull("value")
        .put("precision", "APPROXIMATE")
        .put("timeSpecified", false)
        .put("confidence", 0.5)
        .putArray("ambiguityReasons")
        .add("IMPRECISE_DATE");
    proposal.putArray("ambiguityReasons").add("IMPRECISE_DATE");
    ((ObjectNode) proposal.path("itemCandidates").get(0)).putNull("dueDateCandidateId");

    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.USER_RESOLVED);
  }

  @Test
  void versionTwoDanglingAndDuplicateDateIdentitiesAreUnclassifiable() {
    proposal = versionTwoTaskProposal();
    context = versionTwoReviewContext();
    ((ObjectNode) proposal.path("itemCandidates").get(0)).put("dueDateCandidateId", "date-missing");

    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);

    proposal = versionTwoTaskProposal();
    proposal
        .withArray("dateCandidates")
        .addObject()
        .put("candidateId", "date-1")
        .put("surfaceText", "11.30")
        .put("value", "2026-11-30")
        .put("precision", "DATE_ONLY")
        .put("timeSpecified", false)
        .put("confidence", 0.7)
        .putArray("ambiguityReasons");

    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
  }

  @Test
  void exactDoesNotGuessADueForMixedItemsOrAnImpreciseDate() {
    proposal
        .withArray("itemCandidates")
        .addObject()
        .put("candidateId", "item-2")
        .put("kind", "EVENT")
        .put("title", "Meeting")
        .putNull("sourceSpan")
        .putNull("action")
        .putNull("object")
        .put("confidence", 0.8);
    ((ObjectNode) selection.path("items").get(0)).putNull("due");
    selection
        .withArray("items")
        .addObject()
        .put("kind", "EVENT")
        .put("title", "Meeting")
        .putNull("due");

    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.EXACT);

    proposal = taskProposal();
    proposal
        .putArray("dateCandidates")
        .addObject()
        .put("surfaceText", "around the weekend")
        .putNull("value")
        .put("precision", "APPROXIMATE")
        .put("timeSpecified", false)
        .put("confidence", 0.5)
        .putArray("ambiguityReasons")
        .add("IMPRECISE_DATE");
    proposal.putArray("ambiguityReasons").add("IMPRECISE_DATE");
    selection = exactSelection();
    ((ObjectNode) selection.path("items").get(0)).putNull("due");

    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.EXACT);
  }

  @Test
  void correctedReportsOnlyComparableFieldFamilies() {
    selection.put("selectedType", "IDEA").put("title", "Changed title");
    selection.putArray("selectedTags");
    ObjectNode item = (ObjectNode) selection.path("items").get(0);
    item.put("kind", "IDEA").put("title", "Changed title").putNull("due");

    var result = classifier.classify(proposal, selection, context);

    assertThat(result.outcome()).isEqualTo(Outcome.CORRECTED);
    assertThat(result.correctedFields().type()).isTrue();
    assertThat(result.correctedFields().title()).isTrue();
    assertThat(result.correctedFields().tags()).isTrue();
    assertThat(result.correctedFields().items()).isTrue();
    assertThat(result.correctedFields().due()).isTrue();
  }

  @Test
  void unresolvedDefaultBecomesUserResolvedOnlyForTheSameRevision() {
    proposal.putArray("typeCandidates").addObject().put("value", "UNKNOWN").put("score", 0.9);
    proposal.putArray("itemCandidates");
    selection.putArray("selectedTags");
    ((ObjectNode) selection.path("items").get(0)).putNull("due");

    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.USER_RESOLVED);

    selection.put("expectedMemoRevision", 2);
    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
  }

  @Test
  void relationsAndInconsistentStoredDateAreUnclassifiable() {
    proposal
        .putArray("relationCandidates")
        .addObject()
        .put("sourceCandidateId", "item-1")
        .put("targetType", "TAG")
        .put("targetId", TAG_ID)
        .put("relationType", "RELATED_TO")
        .put("score", 0.8);
    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);

    proposal.putArray("relationCandidates");
    ((ObjectNode) selection.at("/items/0/due")).put("dueLocalDate", "2026-11-26");
    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
  }

  @Test
  void eventScheduleSelectionIsUnclassifiableUntilTemporalReviewPolicyIsVersioned() {
    ObjectNode item = (ObjectNode) selection.path("items").get(0);
    item.put("kind", "EVENT").putNull("due");
    item.putObject("eventSchedule")
        .put("mode", "TIMED")
        .put("originalStart", "2026-08-24T18:00:00+09:00")
        .putNull("originalEnd")
        .put("timeZone", "Asia/Seoul")
        .put("startInstant", "2026-08-24T09:00:00Z")
        .putNull("endInstant")
        .putNull("startLocalDate")
        .putNull("endLocalDateExclusive");

    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
  }

  @Test
  void versionThreeEventBindingRemainsUnclassifiableWithoutAnApprovedComparisonPolicy() {
    proposal = versionTwoTaskProposal().put("schemaVersion", "3");
    ((ObjectNode) proposal.at("/typeCandidates/0")).put("value", "EVENT");
    ((ObjectNode) proposal.at("/dateCandidates/0"))
        .put("value", "2026-08-24T18:00:00+09:00")
        .put("precision", "EXACT_TIME")
        .put("timeSpecified", true);
    ObjectNode proposalItem = (ObjectNode) proposal.at("/itemCandidates/0");
    proposalItem
        .put("kind", "EVENT")
        .putNull("dueDateCandidateId")
        .putNull("suggestedEventScheduleCandidateId");
    proposalItem
        .putArray("eventScheduleCandidates")
        .addObject()
        .put("candidateId", "event-schedule-1")
        .put("mode", "TIMED")
        .put("startDateCandidateId", "date-1")
        .putNull("end")
        .put("score", 0.9);

    selection.put("selectedType", "EVENT");
    ObjectNode selectedItem = (ObjectNode) selection.at("/items/0");
    selectedItem.put("kind", "EVENT").putNull("due");
    selectedItem
        .putObject("eventSchedule")
        .put("mode", "TIMED")
        .put("originalStart", "2026-08-24T18:00:00+09:00")
        .putNull("originalEnd")
        .put("timeZone", "Asia/Seoul")
        .put("startInstant", "2026-08-24T09:00:00Z")
        .putNull("endInstant")
        .putNull("startLocalDate")
        .putNull("endLocalDateExclusive");
    context = new ReviewContext("3", MEMO_ID, 1, MEMO_ID, 1);

    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
  }

  @Test
  void malformedAndUnknownProposalFieldsAreUnclassifiableBeforeUserResolution() {
    proposal.putArray("typeCandidates").addObject().put("value", "UNKNOWN").put("score", 0.9);
    proposal.putArray("itemCandidates");

    proposal.remove("providerMetadata");
    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);

    proposal = taskProposal();
    proposal.put("futureRootField", true);
    assertThat(classifier.classify(proposal, selection, context).outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
  }

  @Test
  void mismatchedRunApplicationAndProposalContextsAreUnclassifiable() {
    UUID otherMemoId = UUID.fromString("30000000-0000-0000-0000-000000000002");

    assertThat(
            classifier
                .classify(
                    proposal, selection, new ReviewContext("1", otherMemoId, 1, otherMemoId, 1))
                .outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
    assertThat(
            classifier
                .classify(proposal, selection, new ReviewContext("1", MEMO_ID, 1, otherMemoId, 1))
                .outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
    assertThat(
            classifier
                .classify(proposal, selection, new ReviewContext("1", MEMO_ID, 1, MEMO_ID, 2))
                .outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
    assertThat(
            classifier
                .classify(proposal, selection, new ReviewContext("2", MEMO_ID, 1, MEMO_ID, 1))
                .outcome())
        .isEqualTo(Outcome.UNCLASSIFIABLE);
  }

  @Test
  void exactUsesEcmaScriptTrimForNbspBomAndUnicodeSpaceSeparators() {
    proposal
        .withObject("suggestedTitle")
        .put("value", "\u00A0\uFEFF\u2007Submit assignment\u3000\uFEFF");
    proposal
        .withArray("itemCandidates")
        .addObject()
        .put("candidateId", "item-2")
        .put("kind", "INFORMATION")
        .put("title", "\u00A0\uFEFFReference note\u2007")
        .putNull("sourceSpan")
        .putNull("action")
        .putNull("object")
        .put("confidence", 0.7);
    selection
        .withArray("items")
        .addObject()
        .put("kind", "INFORMATION")
        .put("title", "Reference note")
        .putNull("due");
    ((ObjectNode) selection.path("items").get(0)).putNull("due");

    var result = classifier.classify(proposal, selection, context);

    assertThat(result.outcome()).isEqualTo(Outcome.EXACT);
    assertThat(result.correctedFields().any()).isFalse();
  }

  private ObjectNode taskProposal() {
    ObjectNode value = json.createObjectNode();
    value.put("schemaVersion", "1").put("memoId", MEMO_ID.toString()).put("memoRevision", 1);
    value
        .putObject("suggestedTitle")
        .put("value", "Submit assignment")
        .put("confidence", 0.9)
        .put("needsConfirmation", true);
    value.putArray("typeCandidates").addObject().put("value", "TASK").put("score", 0.9);
    value
        .putArray("dateCandidates")
        .addObject()
        .put("surfaceText", "11.25")
        .put("value", "2026-11-25")
        .put("precision", "DATE_ONLY")
        .put("timeSpecified", false)
        .put("confidence", 0.8)
        .putArray("ambiguityReasons");
    ArrayNode tags = value.putArray("tagCandidates");
    tags.addObject()
        .put("existingTagId", TAG_ID)
        .put("canonicalName", "OS")
        .put("matchedAlias", "OS")
        .put("score", 0.9)
        .put("isNewProposal", false);
    tags.addObject()
        .putNull("existingTagId")
        .put("canonicalName", "Project")
        .putNull("matchedAlias")
        .put("score", 0.7)
        .put("isNewProposal", true);
    value
        .putArray("itemCandidates")
        .addObject()
        .put("candidateId", "item-1")
        .put("kind", "TASK")
        .put("title", "Original item title")
        .putNull("sourceSpan")
        .put("action", "submit")
        .put("object", "assignment")
        .put("confidence", 0.9);
    value.putArray("relationCandidates");
    value.putArray("ambiguityReasons");
    value
        .putObject("providerMetadata")
        .put("analyzerVersion", "fake-v1")
        .put("promptVersion", "none")
        .put("localModelVersion", "none")
        .put("embeddingModelVersion", "none")
        .put("routingPolicyVersion", "fake-policy-v1")
        .put("toolCalls", 0);
    return value;
  }

  private ObjectNode exactSelection() {
    ObjectNode value = json.createObjectNode();
    value
        .put("expectedMemoRevision", 1)
        .put("selectedType", "TASK")
        .put("title", "Submit assignment");
    ArrayNode tags = value.putArray("selectedTags");
    tags.addObject()
        .putNull("existingTagId")
        .put("newCanonicalName", "Project")
        .put("normalizedName", "project");
    tags.addObject()
        .put("existingTagId", TAG_ID)
        .putNull("newCanonicalName")
        .putNull("normalizedName");
    ObjectNode item = value.putArray("items").addObject();
    item.put("kind", "TASK").put("title", "Submit assignment");
    item.putObject("due")
        .put("surfaceText", "11.25")
        .put("originalValue", "2026-11-25")
        .put("precision", "DATE_ONLY")
        .put("timeZone", "Asia/Seoul")
        .put("timeSpecified", false)
        .putNull("dueInstant")
        .put("dueLocalDate", "2026-11-25");
    return value;
  }

  private ObjectNode versionTwoTaskProposal() {
    ObjectNode value = taskProposal().put("schemaVersion", "2");
    ((ObjectNode) value.path("dateCandidates").get(0)).put("candidateId", "date-1");
    ((ObjectNode) value.path("itemCandidates").get(0)).put("dueDateCandidateId", "date-1");
    return value;
  }

  private ObjectNode versionThreeTaskProposal() {
    ObjectNode value = versionTwoTaskProposal().put("schemaVersion", "3");
    ObjectNode item = (ObjectNode) value.path("itemCandidates").get(0);
    item.putArray("eventScheduleCandidates");
    item.putNull("suggestedEventScheduleCandidateId");
    return value;
  }

  private ReviewContext reviewContext() {
    return new ReviewContext("1", MEMO_ID, 1, MEMO_ID, 1);
  }

  private ReviewContext versionTwoReviewContext() {
    return new ReviewContext("2", MEMO_ID, 1, MEMO_ID, 1);
  }
}
