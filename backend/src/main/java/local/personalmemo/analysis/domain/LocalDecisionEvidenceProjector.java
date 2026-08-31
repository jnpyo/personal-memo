package local.personalmemo.analysis.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Projects an already validated proposal into bounded evidence that contains no memo text or IDs.
 */
@Component
public final class LocalDecisionEvidenceProjector {
  private static final BigDecimal MIN_TYPE_SCORE = new BigDecimal("0.70");
  private static final BigDecimal MIN_TYPE_MARGIN = new BigDecimal("0.10");
  private static final BigDecimal MIN_TAXONOMY_SCORE = new BigDecimal("0.75");
  private static final Set<String> PRECISE_DATE_KINDS =
      Set.of("EXACT_TIME", "DATE_ONLY", "RELATIVE_EXACT");

  private final ObjectMapper json;
  private final LocalDecisionEvidenceValidator validator;

  public LocalDecisionEvidenceProjector(
      ObjectMapper json, LocalDecisionEvidenceValidator validator) {
    this.json = Objects.requireNonNull(json, "json");
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  public LocalDecisionEvidenceProjection project(JsonNode validatedProposal) {
    try {
      requireValidatedShape(validatedProposal);
      TypeProjection type = typeProjection(validatedProposal.path("typeCandidates"));
      TemporalProjection temporal = temporalProjection(validatedProposal.path("dateCandidates"));
      TaxonomyProjection taxonomy = taxonomyProjection(validatedProposal.path("tagCandidates"));
      ItemProjection items = itemProjection(validatedProposal.path("itemCandidates"));

      ObjectNode evidence = json.createObjectNode();
      evidence.put("version", LocalDecisionEvidenceProjection.EVIDENCE_VERSION);
      ObjectNode typeSummary = evidence.putObject("typeSummary");
      typeSummary.put("candidateCount", type.candidateCount());
      typeSummary.put("leader", type.leader());
      typeSummary.put("leaderScore", type.leaderScore());
      if (type.runnerUpScore() == null) {
        typeSummary.putNull("runnerUpScore");
        typeSummary.putNull("margin");
      } else {
        typeSummary.put("runnerUpScore", type.runnerUpScore());
        typeSummary.put("margin", type.margin());
      }

      evidence
          .putObject("temporalSummary")
          .put("candidateCount", temporal.candidateCount())
          .put("preciseCount", temporal.preciseCount())
          .put("impreciseCount", temporal.impreciseCount())
          .put("explicitTimeCount", temporal.explicitTimeCount());

      ObjectNode taxonomySummary = evidence.putObject("taxonomySummary");
      taxonomySummary.put("candidateCount", taxonomy.candidateCount());
      taxonomySummary.put("newProposalCount", taxonomy.newProposalCount());
      if (taxonomy.strongestScore() == null) {
        taxonomySummary.putNull("strongestScore");
      } else {
        taxonomySummary.put("strongestScore", taxonomy.strongestScore());
      }

      evidence
          .putObject("itemSummary")
          .put("candidateCount", items.candidateCount())
          .put("taskCount", items.taskCount())
          .put("verbPresentCount", items.verbPresentCount())
          .put("referentPresentCount", items.referentPresentCount())
          .put("dueBindingCount", items.dueBindingCount());
      evidence.put("relationCandidateCount", validatedProposal.path("relationCandidates").size());

      validator.validate(evidence);
      List<FallbackReasonCode> reasons =
          fallbackReasons(validatedProposal, type, temporal, taxonomy, items);
      return new LocalDecisionEvidenceProjection(evidence, reasons);
    } catch (DomainException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw DomainException.invalid(
          "INVALID_LOCAL_DECISION_EVIDENCE", "The local decision evidence is invalid.");
    }
  }

  private TypeProjection typeProjection(JsonNode candidates) {
    List<ScoredType> scores = new ArrayList<>();
    for (JsonNode candidate : candidates) {
      scores.add(
          new ScoredType(candidate.path("value").asText(), candidate.path("score").decimalValue()));
    }
    scores.sort(Comparator.comparing(ScoredType::score).reversed());
    ScoredType leader = scores.getFirst();
    BigDecimal runnerUp = scores.size() > 1 ? scores.get(1).score() : null;
    return new TypeProjection(
        scores.size(),
        leader.type(),
        leader.score(),
        runnerUp,
        runnerUp == null ? null : leader.score().subtract(runnerUp));
  }

  private TemporalProjection temporalProjection(JsonNode candidates) {
    int precise = 0;
    int explicitTime = 0;
    for (JsonNode candidate : candidates) {
      if (PRECISE_DATE_KINDS.contains(candidate.path("precision").asText())) {
        precise++;
      }
      if (candidate.path("timeSpecified").asBoolean()) {
        explicitTime++;
      }
    }
    return new TemporalProjection(
        candidates.size(), precise, candidates.size() - precise, explicitTime);
  }

  private TaxonomyProjection taxonomyProjection(JsonNode candidates) {
    int newProposals = 0;
    BigDecimal strongest = null;
    for (JsonNode candidate : candidates) {
      if (candidate.path("isNewProposal").asBoolean()) {
        newProposals++;
      }
      BigDecimal score = candidate.path("score").decimalValue();
      if (strongest == null || score.compareTo(strongest) > 0) {
        strongest = score;
      }
    }
    return new TaxonomyProjection(candidates.size(), newProposals, strongest);
  }

  private ItemProjection itemProjection(JsonNode candidates) {
    int tasks = 0;
    int verbs = 0;
    int referents = 0;
    int dueBindings = 0;
    for (JsonNode candidate : candidates) {
      if (!"TASK".equals(candidate.path("kind").asText())) {
        continue;
      }
      tasks++;
      if (hasText(candidate.path("action"))) {
        verbs++;
      }
      if (hasText(candidate.path("object"))) {
        referents++;
      }
      if (hasText(candidate.path("dueDateCandidateId"))) {
        dueBindings++;
      }
    }
    return new ItemProjection(candidates.size(), tasks, verbs, referents, dueBindings);
  }

  private List<FallbackReasonCode> fallbackReasons(
      JsonNode proposal,
      TypeProjection type,
      TemporalProjection temporal,
      TaxonomyProjection taxonomy,
      ItemProjection items) {
    EnumSet<FallbackReasonCode> reasons = EnumSet.noneOf(FallbackReasonCode.class);
    JsonNode providerMetadata = proposal.path("providerMetadata");
    if ("DEFAULT_FALLBACK".equals(providerMetadata.path("classificationBasis").asText())) {
      reasons.add(FallbackReasonCode.DEFAULT_RECORD_FALLBACK);
    }
    if (positiveCount(providerMetadata.path("unparsedTemporalCueCount"))) {
      reasons.add(FallbackReasonCode.UNPARSED_TEMPORAL_CUE);
    }
    if (positiveCount(providerMetadata.path("unrecognizedActionCueCount"))) {
      reasons.add(FallbackReasonCode.UNRECOGNIZED_ACTION_CUE);
    }
    if (type.leaderScore().compareTo(MIN_TYPE_SCORE) < 0
        || (type.margin() != null && type.margin().compareTo(MIN_TYPE_MARGIN) < 0)) {
      reasons.add(FallbackReasonCode.LOW_TYPE_MARGIN);
    }
    if (taxonomy.newProposalCount() > 0
        || (taxonomy.strongestScore() != null
            && taxonomy.strongestScore().compareTo(MIN_TAXONOMY_SCORE) < 0)) {
      reasons.add(FallbackReasonCode.TAG_UNCERTAINTY);
    }
    if (temporal.impreciseCount() > 0) {
      reasons.add(FallbackReasonCode.DATE_UNCERTAINTY);
    }
    if (items.candidateCount() > 1) {
      reasons.add(FallbackReasonCode.MULTI_INTENT);
    }
    if (("TASK".equals(type.leader()) && items.taskCount() == 0)
        || items.verbPresentCount() < items.taskCount()
        || items.referentPresentCount() < items.taskCount()) {
      reasons.add(FallbackReasonCode.INCOMPLETE_TASK);
    }
    if (topTypeIsNotRepresented(proposal.path("itemCandidates"), type.leader())) {
      reasons.add(FallbackReasonCode.LOCAL_CONFLICT);
    }
    addDeclaredReasons(reasons, proposal.path("ambiguityReasons"));
    for (JsonNode date : proposal.path("dateCandidates")) {
      addDeclaredReasons(reasons, date.path("ambiguityReasons"));
    }
    return List.copyOf(reasons);
  }

  private void addDeclaredReasons(
      EnumSet<FallbackReasonCode> fallbackReasons, JsonNode ambiguityReasons) {
    for (JsonNode reason : ambiguityReasons) {
      switch (AmbiguityReason.valueOf(reason.asText())) {
        case LOW_TYPE_MARGIN -> fallbackReasons.add(FallbackReasonCode.LOW_TYPE_MARGIN);
        case LOW_TAG_SIMILARITY, TAG_CONFLICT, NEW_TOPIC ->
            fallbackReasons.add(FallbackReasonCode.TAG_UNCERTAINTY);
        case MISSING_YEAR, MISSING_TIME, IMPRECISE_DATE, CONFLICTING_DATES ->
            fallbackReasons.add(FallbackReasonCode.DATE_UNCERTAINTY);
        case UNRESOLVED_REFERENCE -> fallbackReasons.add(FallbackReasonCode.UNRESOLVED_REFERENCE);
        case MISSING_ACTION, MISSING_OBJECT ->
            fallbackReasons.add(FallbackReasonCode.INCOMPLETE_TASK);
        case MULTI_INTENT -> fallbackReasons.add(FallbackReasonCode.MULTI_INTENT);
        case CANDIDATE_LIMIT_EXCEEDED -> fallbackReasons.add(FallbackReasonCode.CANDIDATE_LIMIT);
        case LOCAL_CLOUD_CONFLICT -> fallbackReasons.add(FallbackReasonCode.LOCAL_CONFLICT);
      }
    }
  }

  private boolean topTypeIsNotRepresented(JsonNode items, String topType) {
    if ("UNKNOWN".equals(topType) || items.isEmpty()) {
      return false;
    }
    for (JsonNode item : items) {
      if (topType.equals(item.path("kind").asText())) {
        return false;
      }
    }
    return true;
  }

  private boolean hasText(JsonNode value) {
    return value.isTextual() && !value.asText().isBlank();
  }

  private boolean positiveCount(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0;
  }

  private void requireValidatedShape(JsonNode proposal) {
    if (proposal == null
        || !proposal.isObject()
        || !proposal.path("typeCandidates").isArray()
        || proposal.path("typeCandidates").isEmpty()
        || !proposal.path("dateCandidates").isArray()
        || !proposal.path("tagCandidates").isArray()
        || !proposal.path("itemCandidates").isArray()
        || !proposal.path("relationCandidates").isArray()
        || !proposal.path("ambiguityReasons").isArray()) {
      throw DomainException.invalid(
          "INVALID_LOCAL_DECISION_EVIDENCE", "The local decision evidence is invalid.");
    }
  }

  private record ScoredType(String type, BigDecimal score) {}

  private record TypeProjection(
      int candidateCount,
      String leader,
      BigDecimal leaderScore,
      BigDecimal runnerUpScore,
      BigDecimal margin) {}

  private record TemporalProjection(
      int candidateCount, int preciseCount, int impreciseCount, int explicitTimeCount) {}

  private record TaxonomyProjection(
      int candidateCount, int newProposalCount, BigDecimal strongestScore) {}

  private record ItemProjection(
      int candidateCount,
      int taskCount,
      int verbPresentCount,
      int referentPresentCount,
      int dueBindingCount) {}
}
