package local.personalmemo.analysis.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class DeterministicAmbiguityGate {
  public static final String VERSION = "field-policy-v1";
  private static final double MIN_TYPE_SCORE = 0.70;
  private static final double MIN_TYPE_MARGIN = 0.10;
  private static final double MIN_TAG_SCORE = 0.75;
  private static final Set<AmbiguityReason> CLOUD_SIGNALS =
      EnumSet.of(
          AmbiguityReason.LOW_TYPE_MARGIN,
          AmbiguityReason.LOW_TAG_SIMILARITY,
          AmbiguityReason.TAG_CONFLICT,
          AmbiguityReason.NEW_TOPIC,
          AmbiguityReason.IMPRECISE_DATE,
          AmbiguityReason.CONFLICTING_DATES,
          AmbiguityReason.UNRESOLVED_REFERENCE,
          AmbiguityReason.MISSING_ACTION,
          AmbiguityReason.MISSING_OBJECT,
          AmbiguityReason.MULTI_INTENT,
          AmbiguityReason.CANDIDATE_LIMIT_EXCEEDED,
          AmbiguityReason.LOCAL_CLOUD_CONFLICT);

  public AnalysisRoute route(Collection<AmbiguityReason> signals) {
    Objects.requireNonNull(signals, "signals");
    return signals.stream().anyMatch(CLOUD_SIGNALS::contains)
        ? AnalysisRoute.CLOUD_ENRICH
        : AnalysisRoute.LOCAL_REVIEW;
  }

  public String version() {
    return VERSION;
  }

  /**
   * Reconstructs deterministic routing signals from structured candidates as well as the
   * analyzer-declared summary. The server therefore does not rely on an analyzer faithfully copying
   * every field-level warning into the top-level array.
   */
  public List<AmbiguityReason> routingSignals(JsonNode proposal) {
    Objects.requireNonNull(proposal, "proposal");
    EnumSet<AmbiguityReason> signals = EnumSet.noneOf(AmbiguityReason.class);
    addReasons(signals, proposal.path("ambiguityReasons"));

    for (JsonNode date : proposal.path("dateCandidates")) {
      addReasons(signals, date.path("ambiguityReasons"));
      if (Set.of("APPROXIMATE", "UNKNOWN").contains(date.path("precision").asText())) {
        signals.add(AmbiguityReason.IMPRECISE_DATE);
      }
    }

    List<Double> typeScores = descendingScores(proposal.path("typeCandidates"));
    if (!typeScores.isEmpty()
        && (typeScores.getFirst() < MIN_TYPE_SCORE
            || hasSmallMargin(typeScores, MIN_TYPE_MARGIN))) {
      signals.add(AmbiguityReason.LOW_TYPE_MARGIN);
    }
    for (JsonNode type : proposal.path("typeCandidates")) {
      if ("UNKNOWN".equals(type.path("value").asText())) {
        signals.add(AmbiguityReason.MISSING_ACTION);
      }
    }

    List<Double> tagScores = descendingScores(proposal.path("tagCandidates"));
    if (!tagScores.isEmpty() && tagScores.getFirst() < MIN_TAG_SCORE) {
      signals.add(AmbiguityReason.LOW_TAG_SIMILARITY);
    }
    for (JsonNode tag : proposal.path("tagCandidates")) {
      if (tag.path("isNewProposal").asBoolean()) {
        signals.add(AmbiguityReason.NEW_TOPIC);
      }
    }

    JsonNode items = proposal.path("itemCandidates");
    String topType = topType(proposal.path("typeCandidates"));
    if ("TASK".equals(topType) && items.isEmpty()) {
      signals.add(AmbiguityReason.MISSING_ACTION);
    }
    if (topType != null && !"UNKNOWN".equals(topType) && !items.isEmpty()) {
      boolean topTypeRepresented = false;
      for (JsonNode item : items) {
        if (topType.equals(item.path("kind").asText())) {
          topTypeRepresented = true;
          break;
        }
      }
      if (!topTypeRepresented) {
        signals.add(AmbiguityReason.LOCAL_CLOUD_CONFLICT);
      }
    }
    if (items.size() > 1) {
      signals.add(AmbiguityReason.MULTI_INTENT);
    }
    for (JsonNode item : items) {
      if (!"TASK".equals(item.path("kind").asText())) {
        continue;
      }
      if (!item.path("action").isTextual() || item.path("action").asText().isBlank()) {
        signals.add(AmbiguityReason.MISSING_ACTION);
      }
      if (!item.path("object").isTextual() || item.path("object").asText().isBlank()) {
        signals.add(AmbiguityReason.MISSING_OBJECT);
      }
    }

    return List.copyOf(signals);
  }

  private void addReasons(EnumSet<AmbiguityReason> target, JsonNode reasons) {
    for (JsonNode reason : reasons) {
      target.add(AmbiguityReason.valueOf(reason.asText()));
    }
  }

  private List<Double> descendingScores(JsonNode candidates) {
    List<Double> scores = new ArrayList<>();
    for (JsonNode candidate : candidates) {
      scores.add(candidate.path("score").asDouble());
    }
    scores.sort(Comparator.reverseOrder());
    return List.copyOf(scores);
  }

  private String topType(JsonNode candidates) {
    String value = null;
    double score = -1;
    for (JsonNode candidate : candidates) {
      double candidateScore = candidate.path("score").asDouble();
      if (candidateScore > score) {
        score = candidateScore;
        value = candidate.path("value").asText();
      }
    }
    return value;
  }

  private boolean hasSmallMargin(List<Double> scores, double minimumMargin) {
    return scores.size() > 1 && scores.get(0) - scores.get(1) < minimumMargin;
  }
}
