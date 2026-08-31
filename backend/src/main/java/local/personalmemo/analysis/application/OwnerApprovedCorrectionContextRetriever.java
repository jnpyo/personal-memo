package local.personalmemo.analysis.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.application.ApprovedCorrectionCandidateRepository.CandidateRow;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.Classification;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.Outcome;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.ReviewContext;
import local.personalmemo.analysis.domain.ApprovedCorrectionAnchorPolicy;
import local.personalmemo.analysis.domain.ApprovedCorrectionAnchorPolicy.Anchor;
import local.personalmemo.analysis.domain.ApprovedCorrectionContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds bounded type-only hints from the owner's current, latest applied review selections. */
@Component
public class OwnerApprovedCorrectionContextRetriever {
  private static final Set<String> ITEM_KINDS =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD");
  private static final Set<String> SCHEMA_VERSIONS = Set.of("1", "2", "3");
  private static final Comparator<Anchor> SOURCE_ANCHOR_ORDER =
      Comparator.comparing(ApprovedCorrectionAnchorPolicy::isActionLike)
          .reversed()
          .thenComparing(
              Comparator.comparingInt(
                      (Anchor anchor) -> anchor.text().codePointCount(0, anchor.text().length()))
                  .reversed())
          .thenComparingInt(Anchor::startUtf16);

  private static final Comparator<EligibleMatch> CONTEXT_ORDER =
      Comparator.comparingInt(EligibleMatch::anchorCodePoints)
          .reversed()
          .thenComparing(EligibleMatch::appliedAt, Comparator.reverseOrder())
          .thenComparing(EligibleMatch::applicationId, Comparator.reverseOrder())
          .thenComparingInt(match -> match.anchor().startUtf16());

  private final ApprovedCorrectionCandidateRepository repository;
  private final AnalysisReviewOutcomeClassifier classifier;
  private final ObjectMapper json = JsonMapper.builder().build();

  public OwnerApprovedCorrectionContextRetriever(
      ApprovedCorrectionCandidateRepository repository,
      AnalysisReviewOutcomeClassifier classifier) {
    this.repository = repository;
    this.classifier = classifier;
  }

  public ApprovedCorrectionContext retrieve(
      UUID ownerId, UUID targetMemoId, String targetMemoContent) {
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(targetMemoId, "targetMemoId");
    List<Anchor> targetAnchors = ApprovedCorrectionAnchorPolicy.targetAnchors(targetMemoContent);
    if (targetAnchors.isEmpty()) {
      return emptyContext();
    }

    Map<String, List<EligibleMatch>> matchesByNormalizedAnchor = new HashMap<>();
    int scanned = 0;
    for (CandidateRow row : repository.findLatestCurrentApplied(ownerId, targetMemoId)) {
      if (scanned == ApprovedCorrectionCandidateRepository.MAX_SCAN_CANDIDATES) {
        break;
      }
      scanned++;
      EligibleReview review = eligibleReview(row, targetMemoId);
      if (review == null) {
        continue;
      }
      Set<String> sourceTokens =
          ApprovedCorrectionAnchorPolicy.normalizedSourceTokens(row.sourceContent());
      Anchor anchor =
          targetAnchors.stream()
              .filter(candidate -> sourceTokens.contains(candidate.normalized()))
              .sorted(SOURCE_ANCHOR_ORDER)
              .findFirst()
              .orElse(null);
      if (anchor == null) {
        continue;
      }
      matchesByNormalizedAnchor
          .computeIfAbsent(anchor.normalized(), ignored -> new ArrayList<>())
          .add(
              new EligibleMatch(
                  anchor,
                  review.approvedKind(),
                  row.appliedAt(),
                  row.applicationId(),
                  anchor.text().codePointCount(0, anchor.text().length())));
    }

    List<EligibleMatch> conflictFree = new ArrayList<>();
    for (List<EligibleMatch> matches : matchesByNormalizedAnchor.values()) {
      Set<String> kinds = new HashSet<>();
      matches.forEach(match -> kinds.add(match.approvedKind()));
      if (kinds.size() != 1) {
        continue;
      }
      matches.stream().sorted(CONTEXT_ORDER).findFirst().ifPresent(conflictFree::add);
    }
    conflictFree.sort(CONTEXT_ORDER);

    List<ApprovedCorrectionContext.Signal> signals = new ArrayList<>();
    for (EligibleMatch match : conflictFree) {
      if (signals.size() == ApprovedCorrectionContext.MAX_SIGNALS) {
        break;
      }
      signals.add(
          new ApprovedCorrectionContext.Signal(
              match.anchor().startUtf16(), match.anchor().endUtf16(), match.approvedKind()));
    }
    return new ApprovedCorrectionContext(ApprovedCorrectionContext.CURRENT_VERSION, signals);
  }

  private EligibleReview eligibleReview(CandidateRow row, UUID targetMemoId) {
    try {
      if (row == null
          || row.applicationId() == null
          || row.appliedAt() == null
          || row.sourceMemoId() == null
          || row.sourceMemoId().equals(targetMemoId)
          || !"ACTIVE".equals(row.memoStatus())
          || !"APPLIED".equals(row.applicationStatus())
          || !"APPLIED".equals(row.runStatus())
          || !SCHEMA_VERSIONS.contains(row.runSchemaVersion())
          || !row.sourceMemoId().equals(row.applicationMemoId())
          || !row.sourceMemoId().equals(row.runMemoId())
          || row.sourceCurrentRevision() < 1
          || row.sourceCurrentRevision() != row.applicationMemoRevision()
          || row.sourceCurrentRevision() != row.runMemoRevision()) {
        return null;
      }
      JsonNode parsedProposal = json.readTree(row.proposalJson());
      JsonNode selection = json.readTree(row.selectionJson());
      if (!(parsedProposal instanceof ObjectNode proposal)
          || selection == null
          || !selection.isObject()
          || !proposal.path("relationCandidates").isArray()
          || !proposal.path("relationCandidates").isEmpty()
          || !proposal.path("itemCandidates").isArray()
          || proposal.path("itemCandidates").size() != 1
          || !selection.path("items").isArray()
          || selection.path("items").size() != 1
          || (selection.has("selectedRelations")
              && (!selection.path("selectedRelations").isArray()
                  || !selection.path("selectedRelations").isEmpty()))
          || !selection.path("selectedType").isTextual()
          || !ITEM_KINDS.contains(selection.path("selectedType").asText())) {
        return null;
      }
      Classification classification =
          classifier.classify(
              proposal,
              selection,
              new ReviewContext(
                  row.runSchemaVersion(),
                  row.runMemoId(),
                  row.runMemoRevision(),
                  row.applicationMemoId(),
                  row.applicationMemoRevision()));
      boolean eligible =
          classification.outcome() == Outcome.USER_RESOLVED
              || (classification.outcome() == Outcome.CORRECTED
                  && classification.correctedFields().type());
      return eligible ? new EligibleReview(selection.path("selectedType").asText()) : null;
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private ApprovedCorrectionContext emptyContext() {
    return new ApprovedCorrectionContext(ApprovedCorrectionContext.CURRENT_VERSION, List.of());
  }

  private record EligibleReview(String approvedKind) {}

  private record EligibleMatch(
      Anchor anchor,
      String approvedKind,
      Instant appliedAt,
      UUID applicationId,
      int anchorCodePoints) {}
}
