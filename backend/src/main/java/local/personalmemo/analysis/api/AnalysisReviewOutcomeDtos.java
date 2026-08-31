package local.personalmemo.analysis.api;

import java.time.Instant;
import java.util.List;

public final class AnalysisReviewOutcomeDtos {
  private AnalysisReviewOutcomeDtos() {}

  public record Summary(
      String schemaVersion,
      String comparisonPolicyVersion,
      Cohort cohort,
      ProposalCounters proposals,
      LatestApplicationCounters latestApplications,
      OutcomeCounters outcomes,
      List<AnalysisVersionSummary> byAnalysisVersion) {
    public Summary {
      byAnalysisVersion = List.copyOf(byAnalysisVersion);
    }
  }

  public record Cohort(
      String basis, int days, Instant fromInclusive, Instant toExclusive, int maxProposals) {}

  public record ProposalCounters(
      int total, int withApplication, CurrentStateCounters currentStates) {}

  public record CurrentStateCounters(
      int queued,
      int running,
      int reviewRequired,
      int currentPostponed,
      int failed,
      int stale,
      int applied,
      int rejected,
      int other) {}

  public record LatestApplicationCounters(int none, int applied, int undone) {}

  public record OutcomeCounters(
      int exact,
      int corrected,
      int userResolved,
      int unclassifiable,
      CorrectedFieldCounters correctedFields) {}

  public record CorrectedFieldCounters(int type, int title, int tags, int items, int due) {}

  public record AnalysisVersionSummary(
      String route,
      String analyzerVersion,
      String promptVersion,
      String localModelVersion,
      String embeddingModelVersion,
      String routingPolicyVersion,
      ProposalCounters proposals,
      LatestApplicationCounters latestApplications,
      OutcomeCounters outcomes) {}
}
