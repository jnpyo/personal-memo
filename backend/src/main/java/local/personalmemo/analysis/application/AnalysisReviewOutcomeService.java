package local.personalmemo.analysis.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import local.personalmemo.analysis.api.AnalysisReviewOutcomeDtos.AnalysisVersionSummary;
import local.personalmemo.analysis.api.AnalysisReviewOutcomeDtos.Cohort;
import local.personalmemo.analysis.api.AnalysisReviewOutcomeDtos.CorrectedFieldCounters;
import local.personalmemo.analysis.api.AnalysisReviewOutcomeDtos.CurrentStateCounters;
import local.personalmemo.analysis.api.AnalysisReviewOutcomeDtos.LatestApplicationCounters;
import local.personalmemo.analysis.api.AnalysisReviewOutcomeDtos.OutcomeCounters;
import local.personalmemo.analysis.api.AnalysisReviewOutcomeDtos.ProposalCounters;
import local.personalmemo.analysis.api.AnalysisReviewOutcomeDtos.Summary;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.Classification;
import local.personalmemo.analysis.domain.AnalysisReviewOutcomeClassifier.ReviewContext;
import local.personalmemo.analysis.infrastructure.AnalysisReviewOutcomeRepository;
import local.personalmemo.analysis.infrastructure.AnalysisReviewOutcomeRepository.ReviewOutcomeJdbcRow;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisReviewOutcomeService {
  static final int MAX_PROPOSALS = 1000;
  private static final int MAX_DAYS = 90;
  private static final String SCHEMA_VERSION = "1";
  private static final String COHORT_BASIS = "PROPOSAL_CREATED_AT";

  private static final Comparator<VersionKey> VERSION_ORDER =
      Comparator.comparing(VersionKey::route)
          .thenComparing(VersionKey::analyzerVersion)
          .thenComparing(VersionKey::promptVersion)
          .thenComparing(VersionKey::localModelVersion)
          .thenComparing(VersionKey::embeddingModelVersion)
          .thenComparing(VersionKey::routingPolicyVersion);

  private final AnalysisReviewOutcomeRepository repository;
  private final AnalysisReviewOutcomeClassifier classifier;
  private final CurrentIdentity identity;
  private final ObjectMapper json;

  public AnalysisReviewOutcomeService(
      AnalysisReviewOutcomeRepository repository,
      AnalysisReviewOutcomeClassifier classifier,
      CurrentIdentity identity,
      ObjectMapper json) {
    this.repository = repository;
    this.classifier = classifier;
    this.identity = identity;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public Summary summary(int days) {
    if (days < 1 || days > MAX_DAYS) {
      throw DomainException.invalid(
          "INVALID_REVIEW_OUTCOME_WINDOW", "days must be between 1 and 90.");
    }

    Instant toExclusive = Instant.now();
    Instant fromInclusive = toExclusive.minus(Duration.ofDays(days));
    List<ReviewOutcomeJdbcRow> rows =
        repository.findProposalCohort(
            identity.ownerId(), fromInclusive, toExclusive, MAX_PROPOSALS + 1);
    if (rows.size() > MAX_PROPOSALS) {
      throw DomainException.invalid(
          "REVIEW_OUTCOME_WINDOW_TOO_LARGE",
          "The selected window contains more than 1000 analysis proposals.");
    }

    MutableCounters total = new MutableCounters();
    Map<VersionKey, MutableCounters> byVersion = new HashMap<>();
    for (ReviewOutcomeJdbcRow row : rows) {
      Classification classification = classify(row);
      total.add(row, classification);
      byVersion
          .computeIfAbsent(VersionKey.from(row), ignored -> new MutableCounters())
          .add(row, classification);
    }

    List<AnalysisVersionSummary> versionSummaries = new ArrayList<>();
    byVersion.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(VERSION_ORDER))
        .forEach(
            entry -> {
              VersionKey version = entry.getKey();
              Snapshot counters = entry.getValue().snapshot();
              versionSummaries.add(
                  new AnalysisVersionSummary(
                      version.route(),
                      version.analyzerVersion(),
                      version.promptVersion(),
                      version.localModelVersion(),
                      version.embeddingModelVersion(),
                      version.routingPolicyVersion(),
                      counters.proposals(),
                      counters.latestApplications(),
                      counters.outcomes()));
            });

    Snapshot all = total.snapshot();
    return new Summary(
        SCHEMA_VERSION,
        AnalysisReviewOutcomeClassifier.POLICY_VERSION,
        new Cohort(COHORT_BASIS, days, fromInclusive, toExclusive, MAX_PROPOSALS),
        all.proposals(),
        all.latestApplications(),
        all.outcomes(),
        versionSummaries);
  }

  private Classification classify(ReviewOutcomeJdbcRow row) {
    if (row.applicationStatus() == null) {
      return null;
    }
    return classifier.classify(
        parse(row.proposalJson()),
        parse(row.selectionJson()),
        new ReviewContext(
            row.runSchemaVersion(),
            row.runMemoId(),
            row.runMemoRevision(),
            row.applicationMemoId(),
            row.applicationMemoRevision()));
  }

  private JsonNode parse(String value) {
    if (value == null) {
      return null;
    }
    try {
      return json.readTree(value);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static final class MutableCounters {
    private int total;
    private int withApplication;
    private int queued;
    private int running;
    private int reviewRequired;
    private int currentPostponed;
    private int failed;
    private int stale;
    private int applied;
    private int rejected;
    private int other;
    private int noApplication;
    private int latestApplied;
    private int latestUndone;
    private int exact;
    private int corrected;
    private int userResolved;
    private int unclassifiable;
    private int correctedType;
    private int correctedTitle;
    private int correctedTags;
    private int correctedItems;
    private int correctedDue;

    void add(ReviewOutcomeJdbcRow row, Classification classification) {
      total++;
      addRunStatus(row.runStatus());
      addApplicationStatus(row.applicationStatus());
      if (classification == null) {
        return;
      }
      withApplication++;
      addOutcome(classification);
    }

    private void addRunStatus(String status) {
      switch (status) {
        case "QUEUED" -> queued++;
        case "RUNNING" -> running++;
        case "REVIEW_REQUIRED" -> reviewRequired++;
        case "POSTPONED" -> currentPostponed++;
        case "FAILED" -> failed++;
        case "STALE" -> stale++;
        case "APPLIED" -> applied++;
        case "REJECTED" -> rejected++;
        default -> other++;
      }
    }

    private void addApplicationStatus(String status) {
      if (status == null) {
        noApplication++;
      } else if ("APPLIED".equals(status)) {
        latestApplied++;
      } else if ("UNDONE".equals(status)) {
        latestUndone++;
      } else {
        // The current schema excludes this case. Counting it as none preserves the public sum
        // invariant if a future status appears before this policy is versioned.
        noApplication++;
      }
    }

    private void addOutcome(Classification classification) {
      switch (classification.outcome()) {
        case EXACT -> exact++;
        case CORRECTED -> {
          corrected++;
          correctedType += classification.correctedFields().type() ? 1 : 0;
          correctedTitle += classification.correctedFields().title() ? 1 : 0;
          correctedTags += classification.correctedFields().tags() ? 1 : 0;
          correctedItems += classification.correctedFields().items() ? 1 : 0;
          correctedDue += classification.correctedFields().due() ? 1 : 0;
        }
        case USER_RESOLVED -> userResolved++;
        case UNCLASSIFIABLE -> unclassifiable++;
      }
    }

    Snapshot snapshot() {
      if (queued
              + running
              + reviewRequired
              + currentPostponed
              + failed
              + stale
              + applied
              + rejected
              + other
          != total) {
        throw new IllegalStateException("Current proposal state counters lost a proposal.");
      }
      if (exact + corrected + userResolved + unclassifiable != withApplication) {
        throw new IllegalStateException("Review outcome counters lost an application.");
      }
      if (noApplication + latestApplied + latestUndone != total) {
        throw new IllegalStateException("Latest application counters lost a proposal.");
      }
      return new Snapshot(
          new ProposalCounters(
              total,
              withApplication,
              new CurrentStateCounters(
                  queued,
                  running,
                  reviewRequired,
                  currentPostponed,
                  failed,
                  stale,
                  applied,
                  rejected,
                  other)),
          new LatestApplicationCounters(noApplication, latestApplied, latestUndone),
          new OutcomeCounters(
              exact,
              corrected,
              userResolved,
              unclassifiable,
              new CorrectedFieldCounters(
                  correctedType, correctedTitle, correctedTags, correctedItems, correctedDue)));
    }
  }

  private record Snapshot(
      ProposalCounters proposals,
      LatestApplicationCounters latestApplications,
      OutcomeCounters outcomes) {}

  private record VersionKey(
      String route,
      String analyzerVersion,
      String promptVersion,
      String localModelVersion,
      String embeddingModelVersion,
      String routingPolicyVersion) {
    static VersionKey from(ReviewOutcomeJdbcRow row) {
      return new VersionKey(
          row.route(),
          row.analyzerVersion(),
          row.promptVersion(),
          row.localModelVersion(),
          row.embeddingModelVersion(),
          row.routingPolicyVersion());
    }
  }
}
