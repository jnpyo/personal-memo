package local.personalmemo.analysis.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class LocalDecisionEvidenceValidator {
  private static final String INVALID_MESSAGE = "The local decision evidence is invalid.";
  private static final Set<String> ALLOWED_TEXT_VALUES =
      Set.of(
          LocalDecisionEvidenceProjection.EVIDENCE_VERSION,
          "TASK",
          "EVENT",
          "INFORMATION",
          "IDEA",
          "RECORD",
          "UNKNOWN");

  private final LocalDecisionEvidenceSchemaValidator schemaValidator;

  public LocalDecisionEvidenceValidator(LocalDecisionEvidenceSchemaValidator schemaValidator) {
    this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
  }

  public void validate(JsonNode evidence) {
    try {
      schemaValidator.validate(evidence);
      rejectFreeText(evidence);
      validateTypeSummary(evidence.path("typeSummary"));
      validateTemporalSummary(evidence.path("temporalSummary"));
      validateTaxonomySummary(evidence.path("taxonomySummary"));
      validateItemSummary(evidence.path("itemSummary"));
    } catch (DomainException exception) {
      fail();
    } catch (RuntimeException exception) {
      fail();
    }
  }

  public void validateFallbackReasonCodes(JsonNode reasonCodes) {
    try {
      if (reasonCodes == null
          || !reasonCodes.isArray()
          || reasonCodes.isEmpty()
          || reasonCodes.size() > FallbackReasonCode.values().length) {
        fail();
      }
      Set<FallbackReasonCode> unique = new HashSet<>();
      for (JsonNode reason : reasonCodes) {
        if (!reason.isTextual() || !unique.add(FallbackReasonCode.valueOf(reason.asText()))) {
          fail();
        }
      }
    } catch (DomainException exception) {
      fail();
    } catch (RuntimeException exception) {
      fail();
    }
  }

  private void validateTypeSummary(JsonNode summary) {
    int candidateCount = summary.path("candidateCount").intValue();
    JsonNode runnerUp = summary.path("runnerUpScore");
    JsonNode margin = summary.path("margin");
    if (candidateCount == 1) {
      if (!runnerUp.isNull() || !margin.isNull()) {
        fail();
      }
      return;
    }
    if (!runnerUp.isNumber() || !margin.isNumber()) {
      fail();
    }
    BigDecimal leaderScore = summary.path("leaderScore").decimalValue();
    BigDecimal runnerUpScore = runnerUp.decimalValue();
    if (leaderScore.compareTo(runnerUpScore) < 0
        || leaderScore.subtract(runnerUpScore).compareTo(margin.decimalValue()) != 0) {
      fail();
    }
  }

  private void validateTemporalSummary(JsonNode summary) {
    int candidates = summary.path("candidateCount").intValue();
    int precise = summary.path("preciseCount").intValue();
    int imprecise = summary.path("impreciseCount").intValue();
    int explicitTime = summary.path("explicitTimeCount").intValue();
    if (precise + imprecise != candidates || explicitTime > precise) {
      fail();
    }
  }

  private void validateTaxonomySummary(JsonNode summary) {
    int candidates = summary.path("candidateCount").intValue();
    int newProposals = summary.path("newProposalCount").intValue();
    JsonNode strongestScore = summary.path("strongestScore");
    if (newProposals > candidates
        || (candidates == 0 && !strongestScore.isNull())
        || (candidates > 0 && !strongestScore.isNumber())) {
      fail();
    }
  }

  private void validateItemSummary(JsonNode summary) {
    int candidates = summary.path("candidateCount").intValue();
    int tasks = summary.path("taskCount").intValue();
    int verbs = summary.path("verbPresentCount").intValue();
    int referents = summary.path("referentPresentCount").intValue();
    int dueBindings = summary.path("dueBindingCount").intValue();
    if (tasks > candidates || verbs > tasks || referents > tasks || dueBindings > tasks) {
      fail();
    }
  }

  private void rejectFreeText(JsonNode value) {
    if (value.isTextual() && !ALLOWED_TEXT_VALUES.contains(value.asText())) {
      fail();
    }
    if (value.isObject() || value.isArray()) {
      for (JsonNode child : value) {
        rejectFreeText(child);
      }
    }
  }

  private static void fail() {
    throw DomainException.invalid("INVALID_LOCAL_DECISION_EVIDENCE", INVALID_MESSAGE);
  }
}
