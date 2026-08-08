package local.personalmemo.analysis.evaluation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.infrastructure.Draft202012AnalysisProposalSchemaValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class EvaluationV2Evaluator {
  private final DeterministicAmbiguityGate ambiguityGate = new DeterministicAmbiguityGate();
  private final AnalysisProposalSchemaValidator schemaValidator =
      new Draft202012AnalysisProposalSchemaValidator();
  private final AnalysisProposalValidator domainValidator = new AnalysisProposalValidator();
  private final AnalysisProvenance expectedProvenance;
  private final String expectedRoutingPolicyVersion;

  EvaluationV2Evaluator(
      ObjectMapper ignored,
      AnalysisProvenance expectedProvenance,
      String expectedRoutingPolicyVersion) {
    this.expectedProvenance = Objects.requireNonNull(expectedProvenance, "expectedProvenance");
    this.expectedRoutingPolicyVersion =
        Objects.requireNonNull(expectedRoutingPolicyVersion, "expectedRoutingPolicyVersion");
  }

  CaseEvaluation evaluate(
      JsonNode fixture, ObjectNode proposal, UUID memoId, int memoRevision, String content) {
    EvaluationCaseGold gold = EvaluationV2GoldIntegrity.validate(fixture);
    boolean schemaValid = validatesSchema(proposal);
    boolean domainValid = validatesDomain(proposal, memoId, memoRevision, content);

    List<String> actualTypes = textValues(proposal.path("typeCandidates"), "value");
    boolean topTypeCorrect = topTypeCorrect(proposal.path("typeCandidates"), gold.expectedTypes());
    boolean typeSetExact = exactCandidateSet(gold.expectedTypes(), actualTypes);
    Set<String> actualSignals = routingSignals(proposal);
    boolean signalsExact = actualSignals.equals(gold.expectedSignals());
    String actualRoute = route(actualSignals);
    boolean routeCorrect = actualRoute.equals(gold.expectedRoute());
    boolean legacyWrongLocal =
        "LOCAL_REVIEW".equals(actualRoute)
            && (!schemaValid || !domainValid || !routeCorrect || !topTypeCorrect || !signalsExact);

    DateCaseEvaluation dates =
        EvaluationV2Matcher.evaluateDates(
            gold.dates(),
            EvaluationV2GoldParser.actualDates(proposal.path("dateCandidates")),
            gold.content());
    ItemCaseEvaluation items =
        EvaluationV2Matcher.evaluateItems(
            gold.items(),
            EvaluationV2GoldParser.actualItems(proposal.path("itemCandidates")),
            text(proposal.path("suggestedTitle").path("value")));

    boolean overflowExpected = gold.dates().overflow() || gold.items().overflow();
    boolean overflowSignal = actualSignals.contains("CANDIDATE_LIMIT_EXCEEDED");
    boolean missingOverflowSignal = overflowExpected && !overflowSignal;
    boolean localOverflow = "LOCAL_REVIEW".equals(actualRoute) && overflowExpected;
    boolean semanticFalseConfidentLocal =
        "LOCAL_REVIEW".equals(actualRoute)
            && (legacyWrongLocal
                || dates.inventedPreciseDate()
                || !typeSetExact
                || !dates.exactSet()
                || !items.completeSetExact()
                || gold.items().requiresUserInput()
                || (overflowExpected && !overflowSignal));

    return new CaseEvaluation(
        gold.id(),
        gold.split(),
        schemaValid,
        domainValid,
        gold.expectedRoute(),
        actualRoute,
        gold.expectedTypes(),
        actualTypes,
        gold.expectedSignals(),
        actualSignals,
        topTypeCorrect,
        typeSetExact,
        signalsExact,
        legacyWrongLocal,
        dates,
        items,
        overflowExpected,
        overflowSignal,
        missingOverflowSignal,
        localOverflow,
        semanticFalseConfidentLocal);
  }

  private boolean validatesSchema(ObjectNode proposal) {
    try {
      schemaValidator.validate(proposal);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private boolean validatesDomain(
      ObjectNode proposal, UUID memoId, int memoRevision, String content) {
    try {
      domainValidator.validate(
          proposal,
          memoId,
          memoRevision,
          content,
          expectedProvenance,
          expectedRoutingPolicyVersion);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private Set<String> routingSignals(ObjectNode proposal) {
    try {
      LinkedHashSet<String> signals = new LinkedHashSet<>();
      ambiguityGate.routingSignals(proposal).stream().map(Enum::name).forEach(signals::add);
      return Set.copyOf(signals);
    } catch (RuntimeException exception) {
      return Set.of("INVALID_PROPOSAL");
    }
  }

  private String route(Set<String> actualSignals) {
    if (actualSignals.contains("INVALID_PROPOSAL")) {
      return "INVALID";
    }
    try {
      return ambiguityGate
          .route(
              actualSignals.stream()
                  .map(local.personalmemo.analysis.domain.AmbiguityReason::valueOf)
                  .toList())
          .name();
    } catch (RuntimeException exception) {
      return "INVALID";
    }
  }

  private boolean topTypeCorrect(JsonNode candidates, List<String> expectedTypes) {
    if (expectedTypes.isEmpty() || !candidates.isArray() || candidates.isEmpty()) {
      return false;
    }
    double maximum = Double.NEGATIVE_INFINITY;
    Set<String> topValues = new HashSet<>();
    for (JsonNode candidate : candidates) {
      JsonNode score = candidate.path("score");
      String value = text(candidate.path("value"));
      if (value == null || !score.isNumber() || !Double.isFinite(score.asDouble())) {
        return false;
      }
      int comparison = Double.compare(score.asDouble(), maximum);
      if (comparison > 0) {
        maximum = score.asDouble();
        topValues.clear();
        topValues.add(value);
      } else if (comparison == 0) {
        topValues.add(value);
      }
    }
    return topValues.size() == 1 && topValues.contains(expectedTypes.getFirst());
  }

  private boolean exactCandidateSet(List<String> expected, List<String> actual) {
    return expected.size() == new HashSet<>(expected).size()
        && actual.size() == new HashSet<>(actual).size()
        && Set.copyOf(expected).equals(Set.copyOf(actual));
  }

  private List<String> textValues(JsonNode array, String field) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      String text = text(field == null ? value : value.path(field));
      if (text != null) {
        values.add(text);
      }
    }
    return List.copyOf(values);
  }

  private String text(JsonNode value) {
    return value.isTextual() ? value.asText() : null;
  }
}

final class EvaluationV2Matcher {
  private EvaluationV2Matcher() {}

  static DateCaseEvaluation evaluateDates(
      EvaluationDateGoldSet goldSet, List<EvaluationActualDate> actual, String content) {
    List<EvaluationDateGold> emitted = goldSet.emitted();
    boolean[][] mentionEdges = new boolean[actual.size()][emitted.size()];
    boolean[][] semanticEdges = new boolean[actual.size()][emitted.size()];
    for (int actualIndex = 0; actualIndex < actual.size(); actualIndex++) {
      for (int goldIndex = 0; goldIndex < emitted.size(); goldIndex++) {
        EvaluationActualDate candidate = actual.get(actualIndex);
        EvaluationDateGold gold = emitted.get(goldIndex);
        boolean mention = mentionMatches(candidate, gold, content);
        mentionEdges[actualIndex][goldIndex] = mention;
        semanticEdges[actualIndex][goldIndex] = mention && gold.accepts(candidate.semantic());
      }
    }
    int mentionTruePositive = maximumMatching(mentionEdges);
    int semanticTruePositive = maximumMatching(semanticEdges);
    boolean inventedPrecise =
        actual.stream()
            .filter(EvaluationActualDate::declaredPrecise)
            .anyMatch(candidate -> !groundedPrecise(candidate, goldSet.mentions(), content));
    TopOneResult topOne = dateTopOne(actual, goldSet.primary(), content);
    EvaluationCounts mentionCounts =
        new EvaluationCounts(
            mentionTruePositive,
            actual.size() - mentionTruePositive,
            emitted.size() - mentionTruePositive);
    EvaluationCounts semanticCounts =
        new EvaluationCounts(
            semanticTruePositive,
            actual.size() - semanticTruePositive,
            emitted.size() - semanticTruePositive);
    return new DateCaseEvaluation(
        mentionCounts,
        semanticCounts,
        semanticTruePositive == actual.size() && semanticTruePositive == emitted.size(),
        inventedPrecise,
        topOne.eligible(),
        topOne.correct(),
        goldSet.overflow());
  }

  static ItemCaseEvaluation evaluateItems(
      EvaluationItemGoldOptions options,
      List<EvaluationActualItem> actual,
      String actualSuggestedTitle) {
    ItemSetEvaluation best = null;
    for (EvaluationItemGoldSet set :
        options.acceptableSets().stream()
            .sorted(Comparator.comparing(EvaluationItemGoldSet::setId))
            .toList()) {
      ItemSetEvaluation candidate = evaluateItemSet(set, actual, actualSuggestedTitle);
      if (best == null || compareItemSets(candidate, best) > 0) {
        best = candidate;
      }
    }
    if (best == null) {
      best = ItemSetEvaluation.empty(actual.size());
    }
    boolean completeSetExact =
        best.itemCounts().falsePositive() == 0
            && best.itemCounts().falseNegative() == 0
            && best.suggestedTitleExact();
    return new ItemCaseEvaluation(
        best.itemCounts(),
        best.kind(),
        best.title(),
        best.action(),
        best.object(),
        best.sourceSpan(),
        best.suggestedTitle(),
        best.cardinalityExact(),
        completeSetExact,
        best.topOneEligible(),
        best.topOneCorrect(),
        best.overflowOmittedCount(),
        best.unresolvedFieldHallucinationCount(),
        options.resolution());
  }

  private static int compareItemSets(ItemSetEvaluation candidate, ItemSetEvaluation current) {
    int semantic = candidate.score().compareTo(current.score());
    return semantic != 0
        ? semantic
        : Boolean.compare(candidate.suggestedTitleExact(), current.suggestedTitleExact());
  }

  private static boolean mentionMatches(
      EvaluationActualDate actual, EvaluationDateGold gold, String content) {
    EvaluationSpan span = gold.sourceSpan();
    return span.end() <= content.length()
        && content.substring(span.start(), span.end()).equals(gold.surfaceText())
        && gold.surfaceText().equals(actual.surfaceText());
  }

  private static boolean groundedPrecise(
      EvaluationActualDate actual, List<EvaluationDateGold> gold, String content) {
    return actual.semantic() != null
        && gold.stream()
            .anyMatch(
                expected ->
                    mentionMatches(actual, expected, content)
                        && expected.acceptsPrecise(actual.semantic()));
  }

  private static TopOneResult dateTopOne(
      List<EvaluationActualDate> actual, EvaluationDateGold primary, String content) {
    if (primary == null) {
      return new TopOneResult(false, false);
    }
    List<EvaluationActualDate> top = topDates(actual);
    return new TopOneResult(
        true,
        top.size() == 1
            && mentionMatches(top.getFirst(), primary, content)
            && primary.accepts(top.getFirst().semantic()));
  }

  private static List<EvaluationActualDate> topDates(List<EvaluationActualDate> actual) {
    double maximum =
        actual.stream()
            .mapToDouble(EvaluationActualDate::confidence)
            .max()
            .orElse(Double.NEGATIVE_INFINITY);
    return actual.stream()
        .filter(candidate -> Double.compare(candidate.confidence(), maximum) == 0)
        .toList();
  }

  private static int maximumMatching(boolean[][] edges) {
    int goldCount = edges.length == 0 ? 0 : edges[0].length;
    int[] matchedActualByGold = new int[goldCount];
    Arrays.fill(matchedActualByGold, -1);
    int matched = 0;
    for (int actualIndex = 0; actualIndex < edges.length; actualIndex++) {
      if (augment(actualIndex, edges, new boolean[goldCount], matchedActualByGold)) {
        matched++;
      }
    }
    return matched;
  }

  private static boolean augment(
      int actual, boolean[][] edges, boolean[] visited, int[] matchedActualByGold) {
    for (int gold = 0; gold < matchedActualByGold.length; gold++) {
      if (!edges[actual][gold] || visited[gold]) {
        continue;
      }
      visited[gold] = true;
      if (matchedActualByGold[gold] == -1
          || augment(matchedActualByGold[gold], edges, visited, matchedActualByGold)) {
        matchedActualByGold[gold] = actual;
        return true;
      }
    }
    return false;
  }

  private static ItemSetEvaluation evaluateItemSet(
      EvaluationItemGoldSet set, List<EvaluationActualItem> actual, String actualSuggestedTitle) {
    List<EvaluationItemGold> gold = set.emitted();
    int[] mapping = new int[actual.size()];
    Arrays.fill(mapping, -1);
    MappingChoice choice = chooseMapping(actual, gold, 0, mapping, new boolean[gold.size()], null);
    MappingEvaluation metrics = mappingMetrics(actual, gold, choice.mapping());
    EvaluationFieldCounts suggestedTitle = textCounts(set.suggestedTitle(), actualSuggestedTitle);
    boolean suggestedTitleExact = set.suggestedTitle().exact(actualSuggestedTitle);
    TopOneResult topOne = itemTopOne(actual, set.primary());
    return new ItemSetEvaluation(
        set.setId(),
        metrics.itemCounts(),
        metrics.kind(),
        metrics.title(),
        metrics.action(),
        metrics.object(),
        metrics.sourceSpan(),
        suggestedTitle,
        actual.size() == gold.size(),
        suggestedTitleExact,
        topOne.eligible(),
        topOne.correct(),
        set.overflowOmittedCount(),
        metrics.unresolvedFieldHallucinationCount(),
        choice.score());
  }

  private static MappingChoice chooseMapping(
      List<EvaluationActualItem> actual,
      List<EvaluationItemGold> gold,
      int actualIndex,
      int[] mapping,
      boolean[] usedGold,
      MappingChoice best) {
    if (actualIndex == actual.size()) {
      int[] snapshot = Arrays.copyOf(mapping, mapping.length);
      MappingScore score = mappingScore(actual, gold, snapshot);
      return best == null || score.compareTo(best.score()) > 0
          ? new MappingChoice(snapshot, score)
          : best;
    }

    mapping[actualIndex] = -1;
    best = chooseMapping(actual, gold, actualIndex + 1, mapping, usedGold, best);
    for (int goldIndex = 0; goldIndex < gold.size(); goldIndex++) {
      if (usedGold[goldIndex]) {
        continue;
      }
      usedGold[goldIndex] = true;
      mapping[actualIndex] = goldIndex;
      best = chooseMapping(actual, gold, actualIndex + 1, mapping, usedGold, best);
      usedGold[goldIndex] = false;
    }
    mapping[actualIndex] = -1;
    return best;
  }

  private static MappingScore mappingScore(
      List<EvaluationActualItem> actual, List<EvaluationItemGold> gold, int[] mapping) {
    int exactItems = 0;
    int spanExact = 0;
    int spanOverlap = 0;
    int kindExact = 0;
    int actionExact = 0;
    int objectExact = 0;
    int titleExact = 0;
    int matchedPairs = 0;
    for (int actualIndex = 0; actualIndex < mapping.length; actualIndex++) {
      int goldIndex = mapping[actualIndex];
      if (goldIndex < 0) {
        continue;
      }
      matchedPairs++;
      EvaluationActualItem candidate = actual.get(actualIndex);
      EvaluationItemGold expected = gold.get(goldIndex);
      if (itemExact(candidate, expected)) exactItems++;
      if (expected.sourceSpan().exact(candidate.sourceSpan()) && candidate.sourceSpan() != null) {
        spanExact++;
      }
      spanOverlap += expected.sourceSpan().bestOverlap(candidate.sourceSpan());
      if (Objects.equals(candidate.kind(), expected.kind())) kindExact++;
      if (expected.action().exact(candidate.action())) actionExact++;
      if (expected.object().exact(candidate.object())) objectExact++;
      if (expected.title().exact(candidate.title())) titleExact++;
    }
    return new MappingScore(
        exactItems,
        spanExact,
        spanOverlap,
        kindExact,
        actionExact,
        objectExact,
        titleExact,
        matchedPairs);
  }

  private static MappingEvaluation mappingMetrics(
      List<EvaluationActualItem> actual, List<EvaluationItemGold> gold, int[] mapping) {
    boolean[] usedGold = new boolean[gold.size()];
    int exactItems = 0;
    EvaluationFieldCounts kind = EvaluationFieldCounts.zero();
    EvaluationFieldCounts title = EvaluationFieldCounts.zero();
    EvaluationFieldCounts action = EvaluationFieldCounts.zero();
    EvaluationFieldCounts object = EvaluationFieldCounts.zero();
    EvaluationFieldCounts sourceSpan = EvaluationFieldCounts.zero();
    int unresolvedFieldHallucinationCount = 0;
    boolean hasUnresolvedAction =
        gold.stream().anyMatch(item -> item.action().state() == EvaluationTextState.UNRESOLVED);
    boolean hasUnresolvedObject =
        gold.stream().anyMatch(item -> item.object().state() == EvaluationTextState.UNRESOLVED);

    for (int actualIndex = 0; actualIndex < mapping.length; actualIndex++) {
      EvaluationActualItem candidate = actual.get(actualIndex);
      int goldIndex = mapping[actualIndex];
      if (goldIndex < 0) {
        kind = kind.add(candidate.kind() == null ? zero() : fp());
        title = title.add(candidate.title() == null ? zero() : fp());
        action = action.add(candidate.action() == null ? zero() : fp());
        object = object.add(candidate.object() == null ? zero() : fp());
        sourceSpan = sourceSpan.add(candidate.sourceSpan() == null ? zero() : fp());
        if (hasUnresolvedAction && candidate.action() != null) {
          unresolvedFieldHallucinationCount++;
        }
        if (hasUnresolvedObject && candidate.object() != null) {
          unresolvedFieldHallucinationCount++;
        }
        continue;
      }
      usedGold[goldIndex] = true;
      EvaluationItemGold expected = gold.get(goldIndex);
      if (itemExact(candidate, expected)) exactItems++;
      kind = kind.add(equalValue(expected.kind(), candidate.kind()));
      title = title.add(textCounts(expected.title(), candidate.title()));
      action = action.add(textCounts(expected.action(), candidate.action()));
      object = object.add(textCounts(expected.object(), candidate.object()));
      sourceSpan = sourceSpan.add(spanCounts(expected.sourceSpan(), candidate.sourceSpan()));
      unresolvedFieldHallucinationCount +=
          unresolvedHallucination(expected.action(), candidate.action());
      unresolvedFieldHallucinationCount +=
          unresolvedHallucination(expected.object(), candidate.object());
    }

    for (int goldIndex = 0; goldIndex < gold.size(); goldIndex++) {
      if (usedGold[goldIndex]) {
        continue;
      }
      EvaluationItemGold expected = gold.get(goldIndex);
      kind = kind.add(fn());
      title = title.add(missingTextCounts(expected.title()));
      action = action.add(missingTextCounts(expected.action()));
      object = object.add(missingTextCounts(expected.object()));
      sourceSpan = sourceSpan.add(missingSpanCounts(expected.sourceSpan()));
    }

    EvaluationCounts itemCounts =
        new EvaluationCounts(exactItems, actual.size() - exactItems, gold.size() - exactItems);
    return new MappingEvaluation(
        itemCounts, kind, title, action, object, sourceSpan, unresolvedFieldHallucinationCount);
  }

  private static boolean itemExact(EvaluationActualItem actual, EvaluationItemGold gold) {
    return Objects.equals(actual.kind(), gold.kind())
        && gold.title().exact(actual.title())
        && gold.action().exact(actual.action())
        && gold.object().exact(actual.object())
        && gold.sourceSpan().exact(actual.sourceSpan());
  }

  private static TopOneResult itemTopOne(
      List<EvaluationActualItem> actual, EvaluationItemGold primary) {
    if (primary == null) {
      return new TopOneResult(false, false);
    }
    double maximum =
        actual.stream()
            .mapToDouble(EvaluationActualItem::confidence)
            .max()
            .orElse(Double.NEGATIVE_INFINITY);
    List<EvaluationActualItem> top =
        actual.stream()
            .filter(candidate -> Double.compare(candidate.confidence(), maximum) == 0)
            .toList();
    return new TopOneResult(true, top.size() == 1 && itemExact(top.getFirst(), primary));
  }

  private static EvaluationFieldCounts textCounts(
      EvaluationTextExpectation expected, String actual) {
    return switch (expected.state()) {
      case VALUE ->
          expected.exact(actual)
              ? tp()
              : actual == null ? fn() : new EvaluationFieldCounts(0, 1, 1);
      case ABSENT -> actual == null ? zero() : fp();
      case UNRESOLVED -> zero();
    };
  }

  private static EvaluationFieldCounts missingTextCounts(EvaluationTextExpectation expected) {
    return expected.state() == EvaluationTextState.VALUE ? fn() : zero();
  }

  private static int unresolvedHallucination(EvaluationTextExpectation expected, String actual) {
    return expected.state() == EvaluationTextState.UNRESOLVED && actual != null ? 1 : 0;
  }

  private static EvaluationFieldCounts spanCounts(
      EvaluationSpanExpectation expected, EvaluationSpan actual) {
    return switch (expected.requirement()) {
      case REQUIRED ->
          expected.exact(actual)
              ? tp()
              : actual == null ? fn() : new EvaluationFieldCounts(0, 1, 1);
      case ABSENT -> actual == null ? zero() : fp();
      case OPTIONAL -> actual == null ? zero() : expected.exact(actual) ? tp() : fp();
    };
  }

  private static EvaluationFieldCounts missingSpanCounts(EvaluationSpanExpectation expected) {
    return expected.requirement() == EvaluationSpanRequirement.REQUIRED ? fn() : zero();
  }

  private static EvaluationFieldCounts equalValue(String expected, String actual) {
    return Objects.equals(expected, actual)
        ? tp()
        : actual == null ? fn() : new EvaluationFieldCounts(0, 1, 1);
  }

  private static EvaluationFieldCounts tp() {
    return new EvaluationFieldCounts(1, 0, 0);
  }

  private static EvaluationFieldCounts fp() {
    return new EvaluationFieldCounts(0, 1, 0);
  }

  private static EvaluationFieldCounts fn() {
    return new EvaluationFieldCounts(0, 0, 1);
  }

  private static EvaluationFieldCounts zero() {
    return EvaluationFieldCounts.zero();
  }

  private record TopOneResult(boolean eligible, boolean correct) {}

  private record MappingChoice(int[] mapping, MappingScore score) {}

  private record MappingScore(
      int exactItems,
      int spanExact,
      int spanOverlap,
      int kindExact,
      int actionExact,
      int objectExact,
      int titleExact,
      int matchedPairs)
      implements Comparable<MappingScore> {
    @Override
    public int compareTo(MappingScore other) {
      return Comparator.comparingInt(MappingScore::exactItems)
          .thenComparingInt(MappingScore::spanExact)
          .thenComparingInt(MappingScore::spanOverlap)
          .thenComparingInt(MappingScore::kindExact)
          .thenComparingInt(MappingScore::actionExact)
          .thenComparingInt(MappingScore::objectExact)
          .thenComparingInt(MappingScore::titleExact)
          .thenComparingInt(MappingScore::matchedPairs)
          .compare(this, other);
    }
  }

  private record MappingEvaluation(
      EvaluationCounts itemCounts,
      EvaluationFieldCounts kind,
      EvaluationFieldCounts title,
      EvaluationFieldCounts action,
      EvaluationFieldCounts object,
      EvaluationFieldCounts sourceSpan,
      int unresolvedFieldHallucinationCount) {}

  private record ItemSetEvaluation(
      String setId,
      EvaluationCounts itemCounts,
      EvaluationFieldCounts kind,
      EvaluationFieldCounts title,
      EvaluationFieldCounts action,
      EvaluationFieldCounts object,
      EvaluationFieldCounts sourceSpan,
      EvaluationFieldCounts suggestedTitle,
      boolean cardinalityExact,
      boolean suggestedTitleExact,
      boolean topOneEligible,
      boolean topOneCorrect,
      int overflowOmittedCount,
      int unresolvedFieldHallucinationCount,
      MappingScore score) {
    static ItemSetEvaluation empty(int actualCount) {
      return new ItemSetEvaluation(
          "",
          new EvaluationCounts(0, actualCount, 0),
          EvaluationFieldCounts.zero(),
          EvaluationFieldCounts.zero(),
          EvaluationFieldCounts.zero(),
          EvaluationFieldCounts.zero(),
          EvaluationFieldCounts.zero(),
          EvaluationFieldCounts.zero(),
          actualCount == 0,
          false,
          false,
          false,
          0,
          0,
          new MappingScore(0, 0, 0, 0, 0, 0, 0, 0));
    }
  }
}

record DateCaseEvaluation(
    EvaluationCounts mentionCounts,
    EvaluationCounts semanticCounts,
    boolean exactSet,
    boolean inventedPreciseDate,
    boolean topOneEligible,
    boolean topOneCorrect,
    boolean overflowExpected) {}

record ItemCaseEvaluation(
    EvaluationCounts itemCounts,
    EvaluationFieldCounts kind,
    EvaluationFieldCounts title,
    EvaluationFieldCounts action,
    EvaluationFieldCounts object,
    EvaluationFieldCounts sourceSpan,
    EvaluationFieldCounts suggestedTitle,
    boolean cardinalityExact,
    boolean completeSetExact,
    boolean topOneEligible,
    boolean topOneCorrect,
    int overflowOmittedCount,
    int unresolvedFieldHallucinationCount,
    String resolution) {}

record CaseEvaluation(
    String id,
    String split,
    boolean schemaValid,
    boolean domainValid,
    String expectedRoute,
    String actualRoute,
    List<String> expectedTypes,
    List<String> actualTypes,
    Set<String> expectedSignals,
    Set<String> actualSignals,
    boolean topTypeCorrect,
    boolean typeSetExact,
    boolean signalsExact,
    boolean legacyWrongLocal,
    DateCaseEvaluation dates,
    ItemCaseEvaluation items,
    boolean overflowExpected,
    boolean overflowSignal,
    boolean missingOverflowSignal,
    boolean localOverflow,
    boolean semanticFalseConfidentLocal) {
  boolean isSplit(String value) {
    return split.equals(value);
  }
}
