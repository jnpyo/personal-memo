package local.personalmemo.analysis.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class EvaluationV2Metrics {
  private EvaluationV2Metrics() {}

  static AggregateEvaluation aggregate(List<CaseEvaluation> cases) {
    MutableAggregate aggregate = new MutableAggregate();
    cases.forEach(aggregate::add);
    return aggregate.freeze();
  }

  private static final class MutableAggregate {
    private int caseCount;
    private int schemaValid;
    private int domainValid;
    private int expectedLocalActualLocal;
    private int expectedLocalActualCloud;
    private int expectedCloudActualLocal;
    private int expectedCloudActualCloud;
    private int invalidRoute;
    private int legacyWrongLocal;
    private int topTypeCorrect;
    private int typeSetExactCases;
    private int expectedTypeCandidates;
    private int actualTypeCandidates;
    private int matchedTypeCandidates;
    private int signalTruePositive;
    private int signalFalsePositive;
    private int signalFalseNegative;
    private int signalExactCases;
    private EvaluationCounts dateMentions = new EvaluationCounts(0, 0, 0);
    private EvaluationCounts dateSemantics = new EvaluationCounts(0, 0, 0);
    private int dateExactSets;
    private int inventedPreciseDates;
    private int dateTopOneEligible;
    private int dateTopOneCorrect;
    private EvaluationCounts items = new EvaluationCounts(0, 0, 0);
    private EvaluationFieldCounts itemKind = EvaluationFieldCounts.zero();
    private EvaluationFieldCounts itemTitle = EvaluationFieldCounts.zero();
    private EvaluationFieldCounts itemAction = EvaluationFieldCounts.zero();
    private EvaluationFieldCounts itemObject = EvaluationFieldCounts.zero();
    private EvaluationFieldCounts itemSourceSpan = EvaluationFieldCounts.zero();
    private EvaluationFieldCounts suggestedTitle = EvaluationFieldCounts.zero();
    private int itemCardinalityExact;
    private int itemCompleteSets;
    private int itemTopOneEligible;
    private int itemTopOneCorrect;
    private int overflowOmittedItems;
    private int resolvedItemCases;
    private int userInputNeededItemCases;
    private int overflowItemCases;
    private int semanticFalseConfidentLocal;
    private int localOverflow;

    void add(CaseEvaluation value) {
      caseCount++;
      if (value.schemaValid()) schemaValid++;
      if (value.domainValid()) domainValid++;
      if (value.legacyWrongLocal()) legacyWrongLocal++;
      if (value.topTypeCorrect()) topTypeCorrect++;
      if (value.typeSetExact()) typeSetExactCases++;
      if (value.signalsExact()) signalExactCases++;
      if (value.semanticFalseConfidentLocal()) semanticFalseConfidentLocal++;
      if (value.localOverflow()) localOverflow++;

      boolean expectedLocal = "LOCAL_REVIEW".equals(value.expectedRoute());
      boolean actualLocal = "LOCAL_REVIEW".equals(value.actualRoute());
      boolean actualCloud = "CLOUD_ENRICH".equals(value.actualRoute());
      if (expectedLocal && actualLocal) expectedLocalActualLocal++;
      if (expectedLocal && actualCloud) expectedLocalActualCloud++;
      if (!expectedLocal && actualLocal) expectedCloudActualLocal++;
      if (!expectedLocal && actualCloud) expectedCloudActualCloud++;
      if (!actualLocal && !actualCloud) invalidRoute++;

      expectedTypeCandidates += value.expectedTypes().size();
      actualTypeCandidates += value.actualTypes().size();
      Set<String> actualTypeSet = new HashSet<>(value.actualTypes());
      matchedTypeCandidates +=
          value.expectedTypes().stream().filter(actualTypeSet::contains).count();

      signalTruePositive +=
          value.actualSignals().stream().filter(value.expectedSignals()::contains).count();
      signalFalsePositive +=
          value.actualSignals().stream()
              .filter(signal -> !value.expectedSignals().contains(signal))
              .count();
      signalFalseNegative +=
          value.expectedSignals().stream()
              .filter(signal -> !value.actualSignals().contains(signal))
              .count();

      dateMentions = dateMentions.add(value.dates().mentionCounts());
      dateSemantics = dateSemantics.add(value.dates().semanticCounts());
      if (value.dates().exactSet()) dateExactSets++;
      if (value.dates().inventedPreciseDate()) inventedPreciseDates++;
      if (value.dates().topOneEligible()) dateTopOneEligible++;
      if (value.dates().topOneCorrect()) dateTopOneCorrect++;

      items = items.add(value.items().itemCounts());
      itemKind = itemKind.add(value.items().kind());
      itemTitle = itemTitle.add(value.items().title());
      itemAction = itemAction.add(value.items().action());
      itemObject = itemObject.add(value.items().object());
      itemSourceSpan = itemSourceSpan.add(value.items().sourceSpan());
      suggestedTitle = suggestedTitle.add(value.items().suggestedTitle());
      if (value.items().cardinalityExact()) itemCardinalityExact++;
      if (value.items().completeSetExact()) itemCompleteSets++;
      if (value.items().topOneEligible()) itemTopOneEligible++;
      if (value.items().topOneCorrect()) itemTopOneCorrect++;
      overflowOmittedItems += value.items().overflowOmittedCount();
      switch (value.items().resolution()) {
        case "RESOLVED" -> resolvedItemCases++;
        case "USER_INPUT_NEEDED" -> userInputNeededItemCases++;
        case "OVERFLOW" -> overflowItemCases++;
        default -> throw new IllegalArgumentException("Unknown item resolution.");
      }
    }

    AggregateEvaluation freeze() {
      return new AggregateEvaluation(
          caseCount,
          schemaValid,
          domainValid,
          new RouteConfusion(
              expectedLocalActualLocal,
              expectedLocalActualCloud,
              expectedCloudActualLocal,
              expectedCloudActualCloud,
              invalidRoute),
          legacyWrongLocal,
          topTypeCorrect,
          typeSetExactCases,
          expectedTypeCandidates,
          actualTypeCandidates,
          matchedTypeCandidates,
          new EvaluationFieldCounts(signalTruePositive, signalFalsePositive, signalFalseNegative),
          signalExactCases,
          dateMentions,
          dateSemantics,
          dateExactSets,
          inventedPreciseDates,
          dateTopOneEligible,
          dateTopOneCorrect,
          items,
          itemKind,
          itemTitle,
          itemAction,
          itemObject,
          itemSourceSpan,
          suggestedTitle,
          itemCardinalityExact,
          itemCompleteSets,
          itemTopOneEligible,
          itemTopOneCorrect,
          overflowOmittedItems,
          resolvedItemCases,
          userInputNeededItemCases,
          overflowItemCases,
          semanticFalseConfidentLocal,
          localOverflow);
    }
  }
}

record RouteConfusion(
    int expectedLocalActualLocal,
    int expectedLocalActualCloud,
    int expectedCloudActualLocal,
    int expectedCloudActualCloud,
    int invalidActualRoute) {}

record AggregateEvaluation(
    int caseCount,
    int schemaValidCount,
    int domainValidCount,
    RouteConfusion routeConfusion,
    int legacyWrongLocalCount,
    int topTypeCorrectCount,
    int typeSetExactCaseCount,
    int expectedTypeCandidateCount,
    int actualTypeCandidateCount,
    int matchedTypeCandidateCount,
    EvaluationFieldCounts signals,
    int signalExactCaseCount,
    EvaluationCounts dateMentions,
    EvaluationCounts dateSemantics,
    int dateExactSetCount,
    int inventedPreciseDateCaseCount,
    int dateTopOneEligibleCount,
    int dateTopOneCorrectCount,
    EvaluationCounts items,
    EvaluationFieldCounts itemKind,
    EvaluationFieldCounts itemTitle,
    EvaluationFieldCounts itemAction,
    EvaluationFieldCounts itemObject,
    EvaluationFieldCounts itemSourceSpan,
    EvaluationFieldCounts suggestedTitle,
    int itemCardinalityExactCount,
    int itemCompleteSetExactCount,
    int itemTopOneEligibleCount,
    int itemTopOneCorrectCount,
    int overflowOmittedItemCount,
    int resolvedItemCaseCount,
    int userInputNeededItemCaseCount,
    int overflowItemCaseCount,
    int semanticFalseConfidentLocalCount,
    int localOverflowCount) {
  ObjectNode toJson(ObjectMapper json) {
    ObjectNode value =
        json.createObjectNode()
            .put("caseCount", caseCount)
            .put("schemaValidCount", schemaValidCount)
            .put("domainValidCount", domainValidCount);
    putRate(value, "schemaValidRate", schemaValidCount, caseCount);
    putRate(value, "domainValidRate", domainValidCount, caseCount);

    ObjectNode route = value.putObject("routeConfusion");
    route
        .put("expectedLocalActualLocal", routeConfusion.expectedLocalActualLocal())
        .put("expectedLocalActualCloud", routeConfusion.expectedLocalActualCloud())
        .put("expectedCloudActualLocal", routeConfusion.expectedCloudActualLocal())
        .put("expectedCloudActualCloud", routeConfusion.expectedCloudActualCloud())
        .put("invalidActualRoute", routeConfusion.invalidActualRoute());
    putRate(
        route,
        "accuracy",
        routeConfusion.expectedLocalActualLocal() + routeConfusion.expectedCloudActualCloud(),
        caseCount);
    int actualLocal =
        routeConfusion.expectedLocalActualLocal() + routeConfusion.expectedCloudActualLocal();
    ObjectNode wrongLocal = value.putObject("wrongLocal");
    wrongLocal.put("count", legacyWrongLocalCount);
    putRate(wrongLocal, "rateAmongLocal", legacyWrongLocalCount, actualLocal);
    putRate(wrongLocal, "rateOverall", legacyWrongLocalCount, caseCount);

    ObjectNode type = value.putObject("type");
    type.put("top1CorrectCount", topTypeCorrectCount);
    putRate(type, "top1Accuracy", topTypeCorrectCount, caseCount);
    type.put("candidateSetExactCount", typeSetExactCaseCount)
        .put("expectedCandidateCount", expectedTypeCandidateCount)
        .put("actualCandidateCount", actualTypeCandidateCount)
        .put("matchedCandidateCount", matchedTypeCandidateCount);
    putRate(type, "candidateSetExactRate", typeSetExactCaseCount, caseCount);
    putRate(type, "matchedCandidatePrecision", matchedTypeCandidateCount, actualTypeCandidateCount);
    putRate(type, "expectedCandidateRecall", matchedTypeCandidateCount, expectedTypeCandidateCount);

    value.set("signals", fieldMetrics(json, signals, signalExactCaseCount, caseCount));
    ObjectNode dates = value.putObject("dates");
    dates.set("mentions", countMetrics(json, dateMentions));
    dates.set("semantic", countMetrics(json, dateSemantics));
    dates.put("exactSetCount", dateExactSetCount);
    putRate(dates, "exactSetRate", dateExactSetCount, caseCount);
    dates.put("inventedPreciseDateCaseCount", inventedPreciseDateCaseCount);
    dates.put("top1EligibleCount", dateTopOneEligibleCount);
    dates.put("top1CorrectCount", dateTopOneCorrectCount);
    putRate(dates, "top1Accuracy", dateTopOneCorrectCount, dateTopOneEligibleCount);

    ObjectNode itemMetrics = value.putObject("items");
    itemMetrics.set("exactCandidates", countMetrics(json, items));
    itemMetrics.set("kind", fieldMetrics(json, itemKind, null, null));
    itemMetrics.set("title", fieldMetrics(json, itemTitle, null, null));
    itemMetrics.set("action", fieldMetrics(json, itemAction, null, null));
    itemMetrics.set("object", fieldMetrics(json, itemObject, null, null));
    itemMetrics.set("sourceSpan", fieldMetrics(json, itemSourceSpan, null, null));
    itemMetrics.set("suggestedTitle", fieldMetrics(json, suggestedTitle, null, null));
    itemMetrics.put("cardinalityExactCount", itemCardinalityExactCount);
    putRate(itemMetrics, "cardinalityExactRate", itemCardinalityExactCount, caseCount);
    itemMetrics.put("completeSetExactCount", itemCompleteSetExactCount);
    putRate(itemMetrics, "completeSetExactRate", itemCompleteSetExactCount, caseCount);
    itemMetrics.put("top1EligibleCount", itemTopOneEligibleCount);
    itemMetrics.put("top1CorrectCount", itemTopOneCorrectCount);
    putRate(itemMetrics, "top1Accuracy", itemTopOneCorrectCount, itemTopOneEligibleCount);
    itemMetrics.put("overflowOmittedGoldCount", overflowOmittedItemCount);
    itemMetrics
        .putObject("goldResolutionCaseCounts")
        .put("resolved", resolvedItemCaseCount)
        .put("userInputNeeded", userInputNeededItemCaseCount)
        .put("overflow", overflowItemCaseCount);

    ObjectNode safety = value.putObject("safety");
    safety
        .put("semanticFalseConfidentLocalCount", semanticFalseConfidentLocalCount)
        .put("semanticFalseConfidentLocalEnforced", false)
        .put("localOverflowCount", localOverflowCount);
    return value;
  }

  boolean regressionHardGatePassed() {
    return caseCount > 0
        && schemaValidCount == caseCount
        && domainValidCount == caseCount
        && legacyWrongLocalCount == 0
        && inventedPreciseDateCaseCount == 0
        && localOverflowCount == 0;
  }

  private static ObjectNode countMetrics(ObjectMapper json, EvaluationCounts counts) {
    ObjectNode value =
        json.createObjectNode()
            .put("truePositive", counts.truePositive())
            .put("falsePositive", counts.falsePositive())
            .put("falseNegative", counts.falseNegative());
    putPrecisionRecallF1(
        value, counts.truePositive(), counts.falsePositive(), counts.falseNegative());
    return value;
  }

  private static ObjectNode fieldMetrics(
      ObjectMapper json,
      EvaluationFieldCounts counts,
      Integer exactCases,
      Integer caseDenominator) {
    ObjectNode value =
        json.createObjectNode()
            .put("truePositive", counts.truePositive())
            .put("falsePositive", counts.falsePositive())
            .put("falseNegative", counts.falseNegative());
    putPrecisionRecallF1(
        value, counts.truePositive(), counts.falsePositive(), counts.falseNegative());
    if (exactCases != null && caseDenominator != null) {
      value.put("exactCaseCount", exactCases);
      putRate(value, "exactCaseRate", exactCases, caseDenominator);
    }
    return value;
  }

  private static void putPrecisionRecallF1(
      ObjectNode value, long truePositive, long falsePositive, long falseNegative) {
    Double precision = ratio(truePositive, truePositive + falsePositive);
    Double recall = ratio(truePositive, truePositive + falseNegative);
    putNullable(value, "precision", precision);
    putNullable(value, "recall", recall);
    Double f1 = null;
    if (precision != null && recall != null) {
      f1 = precision + recall == 0 ? 0d : rounded((2 * precision * recall) / (precision + recall));
    }
    putNullable(value, "f1", f1);
  }

  private static void putRate(ObjectNode value, String field, long numerator, long denominator) {
    putNullable(value, field, ratio(numerator, denominator));
  }

  private static void putNullable(ObjectNode value, String field, Double rate) {
    if (rate == null) {
      value.putNull(field);
    } else {
      value.put(field, rate);
    }
  }

  private static Double ratio(long numerator, long denominator) {
    return denominator == 0 ? null : rounded((double) numerator / denominator);
  }

  private static double rounded(double value) {
    return Math.round(value * 1_000_000d) / 1_000_000d;
  }
}

final class EvaluationV2Report {
  private EvaluationV2Report() {}

  static ObjectNode aggregateOnly(
      ObjectMapper json,
      EvaluationReportMetadata metadata,
      Map<String, AggregateEvaluation> splits) {
    ObjectNode report = metadata(json, metadata);
    ObjectNode splitNode = report.putObject("splits");
    splits.forEach((name, aggregate) -> splitNode.set(name, aggregate.toJson(json)));
    return report;
  }

  static ObjectNode withPublicCases(
      ObjectMapper json,
      EvaluationReportMetadata metadata,
      Map<String, AggregateEvaluation> splits,
      List<CaseEvaluation> cases) {
    ObjectNode report = aggregateOnly(json, metadata, splits);
    ArrayNode caseNodes = report.putArray("cases");
    cases.stream().map(value -> publicCase(json, value)).forEach(caseNodes::add);
    return report;
  }

  static ObjectNode gates(
      ObjectMapper json, AggregateEvaluation regression, AggregateEvaluation visibleChallenge) {
    ObjectNode gates = json.createObjectNode();
    gates
        .putObject("regression")
        .put("proposalSchemaAndDomainValidRequired", true)
        .put("wrongLocalMaximum", 0)
        .put("inventedPreciseDateMaximum", 0)
        .put("localOverflowMaximum", 0)
        .put("actualWrongLocal", regression.legacyWrongLocalCount())
        .put("actualInventedPreciseDateCaseCount", regression.inventedPreciseDateCaseCount())
        .put("actualLocalOverflow", regression.localOverflowCount())
        .put("semanticFalseConfidentLocalEnforced", false)
        .put("semanticFalseConfidentLocalPromotion", "REQUIRES_INDEPENDENT_TWO_PERSON_ADJUDICATION")
        .put("passed", regression.regressionHardGatePassed());
    gates
        .putObject("visibleChallenge")
        .put("enforced", false)
        .put("actualWrongLocal", visibleChallenge.legacyWrongLocalCount())
        .put("actualInventedPreciseDateCaseCount", visibleChallenge.inventedPreciseDateCaseCount())
        .put("actualLocalOverflow", visibleChallenge.localOverflowCount())
        .put(
            "semanticFalseConfidentLocalCount", visibleChallenge.semanticFalseConfidentLocalCount())
        .put(
            "reason",
            "Visible synthetic challenge is report-only and is not a blind accuracy estimate.");
    return gates;
  }

  private static ObjectNode metadata(ObjectMapper json, EvaluationReportMetadata metadata) {
    ObjectNode value =
        json.createObjectNode()
            .put("reportVersion", metadata.reportVersion())
            .put("datasetVersion", metadata.datasetVersion())
            .put("analyzerVersion", metadata.analyzerVersion())
            .put("deterministicRulesVersion", metadata.deterministicRulesVersion())
            .put("routingPolicyVersion", metadata.routingPolicyVersion())
            .put("containsRawMemoContent", false);
    value
        .putObject("capabilities")
        .put("dateItemGold", "SCORED")
        .put("dateItemDueBinding", metadata.dateItemCapability());
    return value;
  }

  private static ObjectNode publicCase(ObjectMapper json, CaseEvaluation value) {
    return json.createObjectNode()
        .put("id", value.id())
        .put("split", value.split())
        .put("schemaValid", value.schemaValid())
        .put("domainValid", value.domainValid())
        .put("routeCorrect", value.expectedRoute().equals(value.actualRoute()))
        .put("topTypeCorrect", value.topTypeCorrect())
        .put("typeCandidateSetExact", value.typeSetExact())
        .put("signalsExact", value.signalsExact())
        .put("wrongLocal", value.legacyWrongLocal())
        .put("dateSemanticTruePositive", value.dates().semanticCounts().truePositive())
        .put("dateSemanticFalsePositive", value.dates().semanticCounts().falsePositive())
        .put("dateSemanticFalseNegative", value.dates().semanticCounts().falseNegative())
        .put("dateExactSet", value.dates().exactSet())
        .put("inventedPreciseDate", value.dates().inventedPreciseDate())
        .put("itemTruePositive", value.items().itemCounts().truePositive())
        .put("itemFalsePositive", value.items().itemCounts().falsePositive())
        .put("itemFalseNegative", value.items().itemCounts().falseNegative())
        .put("itemCardinalityExact", value.items().cardinalityExact())
        .put("itemCompleteSetExact", value.items().completeSetExact())
        .put("localOverflow", value.localOverflow())
        .put("semanticFalseConfidentLocal", value.semanticFalseConfidentLocal());
  }
}
