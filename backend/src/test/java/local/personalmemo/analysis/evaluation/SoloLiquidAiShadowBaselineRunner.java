package local.personalmemo.analysis.evaluation;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Explicit, test-scope-only runner for the solo LiquidAI public synthetic shadow baseline.
 *
 * <p>The class name deliberately does not match Maven Surefire's default test patterns. It may only
 * be selected explicitly with {@code -Dtest=SoloLiquidAiShadowBaselineRunner} and the exact opt-in
 * environment contract below.
 */
class SoloLiquidAiShadowBaselineCore {
  static final String OPT_IN_ENV = "PERSONAL_MEMO_SOLO_LIQUIDAI_SHADOW";
  static final String OPT_IN_VALUE = "SOLO_PROVISIONAL_REPORT_ONLY";
  static final String MODEL_ENV = "PERSONAL_MEMO_SOLO_OLLAMA_MODEL";
  static final String DIGEST_ENV = "PERSONAL_MEMO_SOLO_OLLAMA_DIGEST";
  static final String BASE_HEAD_ENV = "PERSONAL_MEMO_SOLO_BASE_HEAD";
  static final String SOURCE_BUNDLE_ENV = "PERSONAL_MEMO_SOLO_SOURCE_BUNDLE_SHA256";
  static final String GPU_NAME_ENV = "PERSONAL_MEMO_SOLO_GPU_NAME";
  static final String GPU_DRIVER_ENV = "PERSONAL_MEMO_SOLO_GPU_DRIVER";
  static final String GPU_TOTAL_ENV = "PERSONAL_MEMO_SOLO_GPU_TOTAL_MIB";
  static final String GPU_BASELINE_USED_ENV = "PERSONAL_MEMO_SOLO_GPU_BASELINE_USED_MIB";
  static final String EXPECTED_MODEL = "hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0";
  static final String EXPECTED_DIGEST =
      "677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822";
  static final String EXPECTED_REGRESSION_SHA256 =
      "1fb50ef1591659582ea779378d8d699d33d1c98a0522baff92d6cd506c35c524";
  static final String EXPECTED_CHALLENGE_SHA256 =
      "cf43ac1f79eea7e5b88f0a0f5623e82a30b468a25024976de8fbb552ed7c1fba";
  static final String EXPECTED_CASE_SCHEMA_SHA256 =
      "029189fec1e3d8f31c52783bcf444a41be6048724627b093d3bd42732c45f2a4";
  static final String EXPECTED_CANONICAL_PROPOSAL_SCHEMA_SHA256 =
      "13aac5622442ed6f7ce5ca57541cdbc015bd6abba3d3e50e57d1b83bb84cbab0";
  static final String EXPECTED_OUTPUT_SCHEMA_SHA256 =
      "8475553a849304f212038388d58f332a7e37f3a6a1361cbe3fb8928455a22453";
  static final String V1_REPORT_SHA256 =
      "360660c5e283f719465262088e91b168a88dea27944a0e61c5fcd065a830b020";
  static final String V2_REPORT_SHA256 =
      "7507690bc6f80c937f382ce428a210540cede1fde621249b5441755b18cb4f26";
  static final String V3_REPORT_SHA256 =
      "f6d6e8de0fc7aad342c0bd68487f1e416f922c75e6ba87cd8463c9b990468fa8";
  static final String V4_REPORT_SHA256 =
      "ce95d1c3a765ffd6805a1062b8cfa26e476f0f1c8dc3cf843407b856a17741f5";

  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
  private static final String CASE_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-case.schema.json";
  private static final String CANONICAL_PROPOSAL_SCHEMA_RESOURCE =
      "/contracts/analysis-proposal.schema.json";
  private static final String OUTPUT_SCHEMA_RESOURCE =
      "/contracts/solo-liquidai-shadow-output.schema.json";
  private static final Path DEFAULT_REPORT_PATH =
      Path.of("target", "evaluation", "solo-liquidai-shadow-baseline-v5.json");
  private static final int MAX_RESOURCE_BYTES = 512 * 1024;
  private static final int MAX_REPORT_BYTES = 512 * 1024;
  private static final int EXPECTED_SPLIT_SIZE = 12;
  private static final int EXPECTED_CASE_COUNT = 24;
  private static final int MEMO_REVISION = 1;
  private static final String ANALYZER_VERSION = "ollama-shadow-v5";
  private static final String PROMPT_VERSION = "solo-liquidai-shadow-prompt-v5";
  private static final String EMBEDDING_VERSION = "none";
  private static final String MEMO_ID_PREFIX = "personal-memo:solo-liquidai-shadow-v5:";
  private static final int MAX_ITEM_CANDIDATES = 3;
  private static final int MAX_DATE_CANDIDATES = 5;
  private static final int MAX_TAG_CANDIDATES = 10;
  private static final int MAX_SLOT_VALUE_LENGTH = 200;
  private static final int MAX_SLOT_ENCODING_LENGTH = 206;
  private static final int MAX_DATE_INTERPRETATION_LENGTH = 224;
  private static final double PRIMARY_TYPE_SCORE = 0.90;
  private static final double SECONDARY_TYPE_SCORE = 0.70;
  private static final double TITLE_CONFIDENCE = 0.85;
  private static final double DATE_CONFIDENCE = 0.85;
  private static final double TAG_SCORE = 0.80;
  private static final double ITEM_CONFIDENCE = 0.85;
  private static final Set<String> CONCRETE_TYPES =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD");
  private static final List<String> AMBIGUITY_REASON_ORDER =
      List.of(
          "LOW_TYPE_MARGIN",
          "NEW_TOPIC",
          "MISSING_YEAR",
          "MISSING_TIME",
          "IMPRECISE_DATE",
          "CONFLICTING_DATES",
          "UNRESOLVED_REFERENCE",
          "MISSING_ACTION",
          "MISSING_OBJECT",
          "MULTI_INTENT",
          "CANDIDATE_LIMIT_EXCEEDED",
          "LOCAL_CLOUD_CONFLICT");
  private static final Pattern GPU_TEXT = Pattern.compile("[A-Za-z0-9 ._()+/\\-]{1,128}");
  private static final Pattern DRIVER_TEXT = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");
  private static final Set<String> FORBIDDEN_REPORT_FIELDS =
      Set.of(
          "content",
          "rawMemo",
          "memoBody",
          "notes",
          "surfaceText",
          "cases",
          "caseId",
          "memoId",
          "ownerId",
          "userId",
          "modelOutput",
          "semanticOutput",
          "modelOutputBytes",
          "exception",
          "message",
          "failureValue",
          "rootAmbiguityReasons",
          "path",
          "paths");

  private final ObjectMapper json;
  private final ShadowConfiguration configuration;
  private final OllamaShadowApi ollama;
  private final Path reportPath;
  private final Map<String, byte[]> resourceOverrides;
  private final FakeAnalyzer fakeAnalyzer;
  private final DeterministicAmbiguityGate ambiguityGate;
  private final AnalysisProvenance liquidProvenance;

  SoloLiquidAiShadowBaselineCore() {
    this(System.getenv(), null, DEFAULT_REPORT_PATH);
  }

  SoloLiquidAiShadowBaselineCore(
      Map<String, String> environment, OllamaShadowApi injectedOllama, Path reportPath) {
    this(environment, injectedOllama, reportPath, Map.of());
  }

  SoloLiquidAiShadowBaselineCore(
      Map<String, String> environment,
      OllamaShadowApi injectedOllama,
      Path reportPath,
      Map<String, byte[]> resourceOverrides) {
    this.json = new ObjectMapper();
    this.configuration = ShadowConfiguration.from(environment);
    this.ollama =
        injectedOllama == null
            ? new OllamaLocalShadowClient(json, configuration.model(), configuration.modelDigest())
            : injectedOllama;
    this.reportPath = Objects.requireNonNull(reportPath, "reportPath").toAbsolutePath().normalize();
    this.resourceOverrides = copyResourceOverrides(resourceOverrides);
    this.fakeAnalyzer = new FakeAnalyzer(json);
    this.ambiguityGate = new DeterministicAmbiguityGate();
    this.liquidProvenance =
        new AnalysisProvenance(
            ANALYZER_VERSION, PROMPT_VERSION, configuration.modelDigest(), EMBEDDING_VERSION);
  }

  @Test
  void writesAggregateOnlySoloProvisionalReport() throws Exception {
    execute();
  }

  ObjectNode execute() throws Exception {
    Path temporaryPath = temporaryPath();
    deleteOutput(reportPath);
    deleteOutput(temporaryPath);

    PinnedResources pinnedResources = verifyPinnedResources();
    ObjectNode outputSchemaNode = (ObjectNode) pinnedResources.outputSchemaNode();
    Schema outputSchema = loadSchema(outputSchemaNode, true);
    Schema caseSchema = loadSchema(pinnedResources.caseSchemaNode(), false);
    List<ShadowFixture> fixtures = loadFixtures(pinnedResources, caseSchema);
    OllamaModelPreflight preflight = ollama.preflight();
    verifyPreflight(preflight);

    OllamaWarmupResult warmup = null;
    OllamaObservedAllocation warmAllocation = OllamaObservedAllocation.notLoaded();
    OllamaObservedAllocation finalAllocation = OllamaObservedAllocation.notLoaded();
    CleanupResult cleanup = CleanupResult.notAttempted();
    EvaluationRun evaluation = null;
    RuntimeException executionFailure = null;
    try {
      warmup = ollama.warmup();
      warmAllocation = ollama.allocation();
      if (!warmAllocation.loaded()) {
        throw new IllegalStateException("Ollama did not keep the exact model loaded after warmup.");
      }
      evaluation = evaluate(fixtures, outputSchemaNode, outputSchema);
      finalAllocation = ollama.allocation();
      if (!finalAllocation.loaded()) {
        throw new IllegalStateException("Ollama model allocation disappeared before reporting.");
      }
    } catch (RuntimeException exception) {
      executionFailure = exception;
    } finally {
      cleanup = restoreModelState();
      deleteOutput(temporaryPath);
      if (!cleanup.restored()) {
        deleteOutput(reportPath);
      }
    }

    if (executionFailure != null) {
      throw executionFailure;
    }
    if (warmup == null || evaluation == null) {
      throw new IllegalStateException("Shadow evaluation did not produce a complete run.");
    }
    if (!cleanup.restored()) {
      throw new IllegalStateException("Ollama model state restoration failed before reporting.");
    }
    require(!Files.exists(temporaryPath), "Scoped v5 report temporary artifact remains.");

    OllamaObservedAllocation observedAllocation =
        maximumAllocation(warmAllocation, finalAllocation);
    ObjectNode report =
        buildReport(
            preflight,
            warmup,
            observedAllocation,
            cleanup,
            evaluation,
            fixtures,
            pinnedResources.integrity(),
            true);
    byte[] serialized =
        (json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n")
            .getBytes(StandardCharsets.UTF_8);
    require(serialized.length <= MAX_REPORT_BYTES, "Shadow report exceeds 512 KiB.");
    assertAggregateOnly(report, serialized, fixtures);
    try {
      publishAtomically(temporaryPath, serialized);
      require(!Files.exists(temporaryPath), "Scoped v5 report temporary artifact remains.");
    } catch (Exception exception) {
      deleteOutput(temporaryPath);
      deleteOutput(reportPath);
      throw exception;
    }
    return report;
  }

  private EvaluationRun evaluate(
      List<ShadowFixture> fixtures, ObjectNode outputSchemaNode, Schema outputSchema) {
    EvaluationV2Evaluator fakeEvaluator =
        new EvaluationV2Evaluator(json, fakeAnalyzer.provenance(), ambiguityGate.version());
    EvaluationV2Evaluator liquidEvaluator =
        new EvaluationV2Evaluator(json, liquidProvenance, ambiguityGate.version());
    List<CaseEvaluation> fakeCases = new ArrayList<>(fixtures.size());
    List<CaseEvaluation> liquidCases = new ArrayList<>(fixtures.size());
    List<Long> fakeLatencyNanos = new ArrayList<>(fixtures.size());
    List<Long> liquidAttemptLatencyNanos = new ArrayList<>(fixtures.size());
    List<Long> liquidSuccessfulResponseLatencyNanos = new ArrayList<>(fixtures.size());
    List<OllamaApiMetrics> liquidApiMetrics = new ArrayList<>(fixtures.size());
    Map<String, Integer> failures = new TreeMap<>();
    Map<SemanticIrFailureCode, Integer> semanticIrFirstViolations =
        new EnumMap<>(SemanticIrFailureCode.class);
    Map<ModelOutputSizeBucket, Integer> modelOutputSizeBuckets =
        new EnumMap<>(ModelOutputSizeBucket.class);
    int responseCount = 0;
    int inferenceSchemaValidCount = 0;
    int semanticIrValidCount = 0;
    int uniqueFailedCaseCount = 0;

    for (ShadowFixture fixture : fixtures) {
      UUID memoId = deterministicMemoId(fixture.split(), fixture.ordinal());
      String memoText = fixture.node().path("content").asText();
      Instant baseInstant = Instant.parse(fixture.node().path("baseInstant").asText());
      String timeZone = fixture.node().path("timeZone").asText();

      long fakeStarted = System.nanoTime();
      ObjectNode fakeProposal =
          fakeAnalyzer.analyze(memoId, MEMO_REVISION, memoText, baseInstant, timeZone);
      fakeLatencyNanos.add(System.nanoTime() - fakeStarted);
      fakeCases.add(
          fakeEvaluator.evaluate(fixture.node(), fakeProposal, memoId, MEMO_REVISION, memoText));

      ObjectNode liquidProposal = json.createObjectNode();
      boolean caseFailed = false;
      long liquidStarted = System.nanoTime();
      try {
        OllamaShadowResult response =
            ollama.analyze(
                memoText, fixture.node().path("baseInstant").asText(), timeZone, outputSchemaNode);
        responseCount++;
        liquidSuccessfulResponseLatencyNanos.add(response.wallDurationNanos());
        liquidApiMetrics.add(response.metrics());
        increment(
            modelOutputSizeBuckets, ModelOutputSizeBucket.forBytes(response.modelOutputBytes()));
        if (!outputSchema.validate(response.semanticOutput()).isEmpty()) {
          increment(failures, "INFERENCE_SCHEMA_INVALID");
          caseFailed = true;
        } else {
          inferenceSchemaValidCount++;
          try {
            liquidProposal = assembleProposal(response.semanticOutput(), memoId);
            semanticIrValidCount++;
          } catch (SemanticIrException exception) {
            increment(failures, "SEMANTIC_IR_INVALID");
            increment(semanticIrFirstViolations, exception.code());
            caseFailed = true;
          } catch (RuntimeException exception) {
            increment(failures, "SEMANTIC_IR_INVALID");
            increment(semanticIrFirstViolations, SemanticIrFailureCode.UNEXPECTED_ASSEMBLY_FAILURE);
            caseFailed = true;
          }
        }
      } catch (OllamaShadowException exception) {
        if (isIntegrationStructuralFailure(exception.failure())) {
          throw exception;
        }
        increment(failures, exception.failure().name());
        caseFailed = true;
      } catch (RuntimeException exception) {
        increment(failures, "UNEXPECTED_SHADOW_FAILURE");
        caseFailed = true;
      } finally {
        liquidAttemptLatencyNanos.add(System.nanoTime() - liquidStarted);
      }

      CaseEvaluation liquidCase =
          liquidEvaluator.evaluate(fixture.node(), liquidProposal, memoId, MEMO_REVISION, memoText);
      if (!liquidCase.schemaValid()) {
        increment(failures, "CANONICAL_SCHEMA_INVALID");
        caseFailed = true;
      }
      if (!liquidCase.domainValid()) {
        increment(failures, "DOMAIN_INVALID");
        caseFailed = true;
      }
      if (caseFailed) {
        uniqueFailedCaseCount++;
      }
      liquidCases.add(liquidCase);
    }

    require(fakeCases.size() == EXPECTED_CASE_COUNT, "Fake evaluation did not cover 24 cases.");
    require(
        liquidCases.size() == EXPECTED_CASE_COUNT, "LiquidAI evaluation did not cover 24 cases.");
    return new EvaluationRun(
        List.copyOf(fakeCases),
        List.copyOf(liquidCases),
        List.copyOf(fakeLatencyNanos),
        List.copyOf(liquidAttemptLatencyNanos),
        List.copyOf(liquidSuccessfulResponseLatencyNanos),
        List.copyOf(liquidApiMetrics),
        Map.copyOf(failures),
        Map.copyOf(semanticIrFirstViolations),
        Map.copyOf(modelOutputSizeBuckets),
        EXPECTED_CASE_COUNT,
        responseCount,
        inferenceSchemaValidCount,
        semanticIrValidCount,
        uniqueFailedCaseCount);
  }

  ObjectNode assembleProposal(ObjectNode semantic, UUID memoId) {
    String title = requiredNonBlankText(semantic, "title", 200, SemanticIrFailureCode.TEXT_INVALID);
    ArrayNode sourceItems = requiredArray(semantic, "items");
    ArrayNode sourceDates = requiredArray(semantic, "dates");
    ArrayNode sourceTopics = requiredArray(semantic, "topicLabels");
    ObjectNode reviewFlags = requiredObject(semantic, "reviewFlags");
    requireSemantic(
        sourceItems.size() <= MAX_ITEM_CANDIDATES, SemanticIrFailureCode.ARRAY_BOUND_EXCEEDED);
    requireSemantic(
        sourceDates.size() <= MAX_DATE_CANDIDATES, SemanticIrFailureCode.ARRAY_BOUND_EXCEEDED);
    requireSemantic(
        sourceTopics.size() <= MAX_TAG_CANDIDATES, SemanticIrFailureCode.ARRAY_BOUND_EXCEEDED);
    boolean itemCoverageExceeded =
        requiredCoverage(
            semantic,
            "itemCoverage",
            "MORE_THAN_THREE",
            sourceItems.size(),
            MAX_ITEM_CANDIDATES,
            SemanticIrFailureCode.ITEM_COVERAGE_CONTRADICTION);
    boolean dateCoverageExceeded =
        requiredCoverage(
            semantic,
            "dateCoverage",
            "MORE_THAN_FIVE",
            sourceDates.size(),
            MAX_DATE_CANDIDATES,
            SemanticIrFailureCode.DATE_COVERAGE_CONTRADICTION);

    LinkedHashSet<String> rootReasons = new LinkedHashSet<>();
    ObjectNode proposal =
        json.createObjectNode()
            .put("schemaVersion", "2")
            .put("memoId", memoId.toString())
            .put("memoRevision", MEMO_REVISION);
    ObjectNode suggestedTitle =
        proposal
            .putObject("suggestedTitle")
            .put("value", title)
            .put("confidence", TITLE_CONFIDENCE)
            .put("needsConfirmation", false);

    ArrayNode dates = proposal.putArray("dateCandidates");
    for (int index = 0; index < sourceDates.size(); index++) {
      ObjectNode source = requireObject(sourceDates.get(index));
      String surfaceText =
          requiredNonBlankText(
              source, "surfaceText", 200, SemanticIrFailureCode.DATE_CONTRADICTION);
      String interpretation =
          requiredText(
              source,
              "interpretation",
              MAX_DATE_INTERPRETATION_LENGTH,
              SemanticIrFailureCode.DATE_CONTRADICTION);
      LinkedHashSet<String> dateReasons = new LinkedHashSet<>();

      ObjectNode target = dates.addObject().put("candidateId", "date-" + (index + 1));
      target.put("surfaceText", surfaceText);
      assembleDateInterpretation(target, interpretation, dateReasons);
      target.put("confidence", DATE_CONFIDENCE);
      putReasons(target.putArray("ambiguityReasons"), dateReasons);
      rootReasons.addAll(dateReasons);
    }

    ArrayNode tags = proposal.putArray("tagCandidates");
    Set<String> seenTopics = new HashSet<>();
    for (JsonNode source : sourceTopics) {
      requireSemantic(source.isTextual(), SemanticIrFailureCode.TOPIC_CONTRADICTION);
      String canonicalName =
          boundedNonBlank(source.asText(), 100, SemanticIrFailureCode.TOPIC_CONTRADICTION);
      requireSemantic(seenTopics.add(canonicalName), SemanticIrFailureCode.TOPIC_CONTRADICTION);
      ObjectNode target = tags.addObject();
      target.putNull("existingTagId");
      target.put("canonicalName", canonicalName);
      target.putNull("matchedAlias");
      target.put("score", TAG_SCORE);
      target.put("isNewProposal", true);
    }
    if (!tags.isEmpty()) {
      rootReasons.add("NEW_TOPIC");
    }

    ArrayNode items = proposal.putArray("itemCandidates");
    LinkedHashSet<String> types = new LinkedHashSet<>();
    for (int index = 0; index < sourceItems.size(); index++) {
      ObjectNode source = requireObject(sourceItems.get(index));
      String kind =
          requiredEnum(source, "kind", CONCRETE_TYPES, SemanticIrFailureCode.TYPE_CONTRADICTION);
      String itemTitle =
          requiredNonBlankText(source, "title", 200, SemanticIrFailureCode.TEXT_INVALID);
      ParsedSlot action = parseSlot(source, "actionSlot", rootReasons);
      ParsedSlot object = parseSlot(source, "objectSlot", rootReasons);
      if (!"TASK".equals(kind)) {
        requireSemantic(
            action.state() == SlotState.ABSENT,
            SemanticIrFailureCode.NON_TASK_ACTION_CONTRADICTION);
      } else {
        if (action.state() == SlotState.ABSENT) {
          rootReasons.add("MISSING_ACTION");
        }
        if (object.state() == SlotState.ABSENT) {
          rootReasons.add("MISSING_OBJECT");
        }
      }
      types.add(kind);
      ObjectNode target = items.addObject().put("candidateId", "item-" + (index + 1));
      target.putNull("dueDateCandidateId");
      target.put("kind", kind);
      target.put("title", itemTitle);
      target.putNull("sourceSpan");
      putNullable(target, "action", action.value());
      putNullable(target, "object", object.value());
      target.put("confidence", ITEM_CONFIDENCE);
    }

    ArrayNode typeCandidates = proposal.putArray("typeCandidates");
    if (items.isEmpty()) {
      typeCandidates.addObject().put("value", "UNKNOWN").put("score", 1.0);
      rootReasons.add("MISSING_ACTION");
    } else {
      int index = 0;
      for (String type : types) {
        typeCandidates
            .addObject()
            .put("value", type)
            .put("score", index++ == 0 ? PRIMARY_TYPE_SCORE : SECONDARY_TYPE_SCORE);
      }
    }

    if (sourceItems.size() > 1) {
      rootReasons.add("MULTI_INTENT");
    }
    if (itemCoverageExceeded || dateCoverageExceeded) {
      rootReasons.add("CANDIDATE_LIMIT_EXCEEDED");
    }
    addReviewFlag(
        reviewFlags, "lowTypeMargin", "LOW_TYPE_MARGIN", rootReasons, typeCandidates.size() >= 2);
    addReviewFlag(
        reviewFlags, "conflictingDates", "CONFLICTING_DATES", rootReasons, sourceDates.size() >= 2);

    proposal.putArray("relationCandidates");
    putReasons(proposal.putArray("ambiguityReasons"), rootReasons);
    suggestedTitle.put("needsConfirmation", !rootReasons.isEmpty());
    proposal
        .putObject("providerMetadata")
        .put("analyzerVersion", liquidProvenance.analyzerVersion())
        .put("promptVersion", liquidProvenance.promptVersion())
        .put("localModelVersion", liquidProvenance.localModelVersion())
        .put("embeddingModelVersion", liquidProvenance.embeddingModelVersion())
        .put("routingPolicyVersion", ambiguityGate.version())
        .put("toolCalls", 0);
    return proposal;
  }

  private boolean requiredCoverage(
      ObjectNode source,
      String field,
      String overflowValue,
      int emittedCount,
      int maximum,
      SemanticIrFailureCode code) {
    String coverage = requiredEnum(source, field, Set.of("COMPLETE", overflowValue), code);
    boolean overflow = overflowValue.equals(coverage);
    if (overflow) {
      requireSemantic(emittedCount == maximum, code);
    }
    return overflow;
  }

  private ParsedSlot parseSlot(ObjectNode source, String field, Set<String> rootReasons) {
    String encoded =
        requiredText(
            source, field, MAX_SLOT_ENCODING_LENGTH, SemanticIrFailureCode.SLOT_ENCODING_INVALID);
    if ("ABSENT".equals(encoded)) {
      return new ParsedSlot(SlotState.ABSENT, null);
    }
    if ("UNRESOLVED".equals(encoded)) {
      rootReasons.add("UNRESOLVED_REFERENCE");
      return new ParsedSlot(SlotState.UNRESOLVED, null);
    }
    if (encoded.startsWith("VALUE:")) {
      String value = encoded.substring("VALUE:".length());
      return new ParsedSlot(
          SlotState.VALUE,
          boundedNonBlank(
              value, MAX_SLOT_VALUE_LENGTH, SemanticIrFailureCode.SLOT_ENCODING_INVALID));
    }
    throw semanticFailure(SemanticIrFailureCode.SLOT_ENCODING_INVALID);
  }

  private void assembleDateInterpretation(
      ObjectNode target, String interpretation, Set<String> dateReasons) {
    if (interpretation.startsWith("DATE_ONLY:")) {
      ParsedPreciseDate parsed =
          parsePreciseDate(
              interpretation,
              "DATE_ONLY:",
              Set.of(
                  DateComponentStatus.COMPLETE,
                  DateComponentStatus.MISSING_YEAR,
                  DateComponentStatus.MISSING_TIME,
                  DateComponentStatus.MISSING_YEAR_AND_TIME));
      parseDate(parsed.value());
      addDateComponentReasons(parsed.componentStatus(), dateReasons);
      target.put("precision", "DATE_ONLY").put("value", parsed.value()).put("timeSpecified", false);
      return;
    }
    if (interpretation.startsWith("EXACT_TIME:")) {
      ParsedPreciseDate parsed =
          parsePreciseDate(interpretation, "EXACT_TIME:", Set.of(DateComponentStatus.COMPLETE));
      parseDateTime(parsed.value());
      target.put("precision", "EXACT_TIME").put("value", parsed.value()).put("timeSpecified", true);
      return;
    }
    if (interpretation.startsWith("RELATIVE_EXACT:")) {
      ParsedPreciseDate parsed =
          parsePreciseDate(interpretation, "RELATIVE_EXACT:", Set.of(DateComponentStatus.COMPLETE));
      parseDateTime(parsed.value());
      target
          .put("precision", "RELATIVE_EXACT")
          .put("value", parsed.value())
          .put("timeSpecified", true);
      return;
    }
    if ("APPROXIMATE".equals(interpretation) || "UNKNOWN".equals(interpretation)) {
      target.put("precision", interpretation).putNull("value").put("timeSpecified", false);
      dateReasons.add("IMPRECISE_DATE");
      if ("UNKNOWN".equals(interpretation)) {
        dateReasons.add("UNRESOLVED_REFERENCE");
      }
      return;
    }
    throw semanticFailure(SemanticIrFailureCode.DATE_CONTRADICTION);
  }

  private ParsedPreciseDate parsePreciseDate(
      String interpretation, String prefix, Set<DateComponentStatus> allowedStatuses) {
    int separator = interpretation.lastIndexOf('|');
    requireSemantic(separator > prefix.length(), SemanticIrFailureCode.DATE_CONTRADICTION);
    String value = interpretation.substring(prefix.length(), separator);
    String statusText = interpretation.substring(separator + 1);
    DateComponentStatus status;
    try {
      status = DateComponentStatus.valueOf(statusText);
    } catch (IllegalArgumentException exception) {
      throw semanticFailure(SemanticIrFailureCode.DATE_CONTRADICTION);
    }
    requireSemantic(allowedStatuses.contains(status), SemanticIrFailureCode.DATE_CONTRADICTION);
    return new ParsedPreciseDate(value, status);
  }

  private void addDateComponentReasons(
      DateComponentStatus componentStatus, Set<String> dateReasons) {
    if (componentStatus == DateComponentStatus.MISSING_YEAR
        || componentStatus == DateComponentStatus.MISSING_YEAR_AND_TIME) {
      dateReasons.add("MISSING_YEAR");
    }
    if (componentStatus == DateComponentStatus.MISSING_TIME
        || componentStatus == DateComponentStatus.MISSING_YEAR_AND_TIME) {
      dateReasons.add("MISSING_TIME");
    }
  }

  private void addReviewFlag(
      ObjectNode flags, String field, String reason, Set<String> rootReasons, boolean allowed) {
    boolean value = requiredBoolean(flags, field, SemanticIrFailureCode.REVIEW_FLAG_CONTRADICTION);
    requireSemantic(!value || allowed, SemanticIrFailureCode.REVIEW_FLAG_CONTRADICTION);
    if (value) {
      rootReasons.add(reason);
    }
  }

  private void putReasons(ArrayNode target, Set<String> reasons) {
    for (String reason : AMBIGUITY_REASON_ORDER) {
      if (reasons.contains(reason)) {
        target.add(reason);
      }
    }
    requireSemantic(
        target.size() == reasons.size(), SemanticIrFailureCode.UNEXPECTED_ASSEMBLY_FAILURE);
  }

  private void putNullable(ObjectNode target, String field, String value) {
    if (value == null) {
      target.putNull(field);
    } else {
      target.put(field, value);
    }
  }

  private ArrayNode requiredArray(JsonNode source, String field) {
    JsonNode value = source.path(field);
    requireSemantic(value.isArray(), SemanticIrFailureCode.SHAPE_INVALID);
    return (ArrayNode) value;
  }

  private ObjectNode requiredObject(JsonNode source, String field) {
    return requireObject(source.path(field));
  }

  private ObjectNode requireObject(JsonNode value) {
    requireSemantic(value.isObject(), SemanticIrFailureCode.SHAPE_INVALID);
    return (ObjectNode) value;
  }

  private boolean requiredBoolean(JsonNode source, String field, SemanticIrFailureCode code) {
    JsonNode value = source.path(field);
    requireSemantic(value.isBoolean(), code);
    return value.booleanValue();
  }

  private String requiredEnum(
      JsonNode source, String field, Set<String> allowed, SemanticIrFailureCode code) {
    String value = requiredText(source, field, 64, code);
    requireSemantic(allowed.contains(value), code);
    return value;
  }

  private String requiredNonBlankText(
      JsonNode source, String field, int maximumLength, SemanticIrFailureCode code) {
    return boundedNonBlank(requiredText(source, field, maximumLength, code), maximumLength, code);
  }

  private String requiredText(
      JsonNode source, String field, int maximumLength, SemanticIrFailureCode code) {
    JsonNode value = source.path(field);
    requireSemantic(value.isTextual(), code);
    String text = value.asText();
    requireSemantic(text.codePointCount(0, text.length()) <= maximumLength, code);
    requireSemantic(text.equals(text.strip()), code);
    return text;
  }

  private String boundedNonBlank(String value, int maximumLength, SemanticIrFailureCode code) {
    requireSemantic(!value.isBlank(), code);
    requireSemantic(value.codePointCount(0, value.length()) <= maximumLength, code);
    requireSemantic(value.equals(value.strip()), code);
    return value;
  }

  private void parseDate(String value) {
    try {
      LocalDate parsed = LocalDate.parse(value);
      requireSemantic(
          value.length() == 10 && parsed.toString().equals(value),
          SemanticIrFailureCode.DATE_CONTRADICTION);
    } catch (DateTimeParseException exception) {
      throw semanticFailure(SemanticIrFailureCode.DATE_CONTRADICTION);
    }
  }

  private void parseDateTime(String value) {
    try {
      OffsetDateTime.parse(value);
    } catch (DateTimeParseException exception) {
      throw semanticFailure(SemanticIrFailureCode.DATE_CONTRADICTION);
    }
  }

  private static void requireSemantic(boolean condition, SemanticIrFailureCode code) {
    if (!condition) {
      throw semanticFailure(code);
    }
  }

  private static SemanticIrException semanticFailure(SemanticIrFailureCode code) {
    return new SemanticIrException(code);
  }

  private ObjectNode buildReport(
      OllamaModelPreflight preflight,
      OllamaWarmupResult warmup,
      OllamaObservedAllocation observedAllocation,
      CleanupResult cleanup,
      EvaluationRun evaluation,
      List<ShadowFixture> fixtures,
      ResourceIntegrity resourceIntegrity,
      boolean scopedTemporaryArtifactAbsent) {
    ObjectNode report =
        json.createObjectNode()
            .put("reportVersion", "5")
            .put("status", "SOLO_PROVISIONAL")
            .put("decisionUse", "REPORT_ONLY")
            .put("metricGateStatus", "NOT_CONFIGURED")
            .put("providerAuthorization", "NOT_AUTHORIZED")
            .put("trainingStatus", "NOT_PERFORMED")
            .put("automaticApplyStatus", "NOT_AUTHORIZED")
            .put("canonicalWriteStatus", "NOT_PERFORMED");

    report
        .putObject("source")
        .put("baseHead", configuration.baseHead())
        .put("sourceBundleSha256", configuration.sourceBundleSha256());
    report
        .putObject("dataset")
        .put("version", "2")
        .put("regressionCount", EXPECTED_SPLIT_SIZE)
        .put("visibleChallengeCount", EXPECTED_SPLIT_SIZE)
        .put("totalCount", EXPECTED_CASE_COUNT)
        .put("dataClass", "PUBLIC_SYNTHETIC_ONLY")
        .put("developmentSetRole", "PUBLIC_VISIBLE_PROMPT_SCHEMA_DEVELOPMENT_ONLY")
        .put("labelEvidence", "NOT_INDEPENDENTLY_ADJUDICATED_OR_BLIND");
    ObjectNode resources = report.putObject("resourceIntegrity");
    resources.put("status", "EXACT_SHA256_AND_REPOSITORY_COPY_MATCH");
    resources.putObject("regressionFixture").put("sha256", resourceIntegrity.regressionSha256());
    resources
        .putObject("visibleChallengeFixture")
        .put("sha256", resourceIntegrity.challengeSha256());
    resources.putObject("evaluationCaseSchema").put("sha256", resourceIntegrity.caseSchemaSha256());
    resources
        .putObject("canonicalProposalSchema")
        .put("sha256", resourceIntegrity.canonicalProposalSchemaSha256());
    resources
        .putObject("inferenceOutputSchema")
        .put("sha256", resourceIntegrity.outputSchemaSha256());
    ObjectNode comparisonReferences = report.putObject("comparisonReferences");
    comparisonReferences
        .putObject("v1")
        .put("artifactName", "solo-liquidai-shadow-baseline.json")
        .put("sha256", V1_REPORT_SHA256)
        .put("use", "DESCRIPTIVE_ONLY_NOT_AN_ACCEPTANCE_GATE");
    comparisonReferences
        .putObject("v2")
        .put("artifactName", "solo-liquidai-shadow-baseline-v2.json")
        .put("sha256", V2_REPORT_SHA256)
        .put("use", "DESCRIPTIVE_ONLY_NOT_AN_ACCEPTANCE_GATE");
    comparisonReferences
        .putObject("v3")
        .put("artifactName", "solo-liquidai-shadow-baseline-v3.json")
        .put("sha256", V3_REPORT_SHA256)
        .put("use", "DESCRIPTIVE_ONLY_NOT_AN_ACCEPTANCE_GATE");
    comparisonReferences
        .putObject("v4")
        .put("artifactName", "solo-liquidai-shadow-baseline-v4.json")
        .put("sha256", V4_REPORT_SHA256)
        .put("use", "DESCRIPTIVE_ONLY_NOT_AN_ACCEPTANCE_GATE");
    report
        .putObject("capabilities")
        .put("dateItemGold", "SCORED")
        .put("dateItemDueBinding", "DISABLED_NULL_ONLY_IN_SHADOW_V5")
        .put("itemSourceSpan", "DISABLED_NULL_ONLY_IN_SHADOW_V5")
        .put("tagRanking", "NOT_SCORED_DATASET_V2")
        .put("relations", "DISABLED_EMPTY_PROPOSAL_ARRAY")
        .put("confidenceScores", "NOT_MODEL_CALIBRATED")
        .put("scoreSource", "FIXED_SERVER_OWNED_TEST_ENVELOPE");

    report
        .putObject("model")
        .put("ollamaVersion", preflight.ollamaVersion())
        .put("name", preflight.model())
        .put("digest", preflight.digest())
        .put("installedSizeBytes", preflight.installedSizeBytes())
        .put("format", preflight.format())
        .put("family", preflight.family())
        .put("parameterSize", preflight.parameterSize())
        .put("quantization", preflight.quantization())
        .put("analyzerVersion", ANALYZER_VERSION)
        .put("promptVersion", PROMPT_VERSION)
        .put("seed", OllamaLocalShadowClient.SEED)
        .put("predictionTokenLimit", OllamaLocalShadowClient.NUM_PREDICT)
        .put("requestedContextLength", OllamaLocalShadowClient.NUM_CONTEXT);

    ObjectNode execution = report.putObject("execution");
    execution
        .put("warmupRequestCount", 1)
        .put("scoredRequestCount", evaluation.requestCount())
        .put("scoredResponseCount", evaluation.responseCount())
        .put("inferenceSchemaValidCount", evaluation.inferenceSchemaValidCount())
        .put("semanticIRValidCount", evaluation.semanticIrValidCount())
        .put("initiallyLoaded", preflight.initiallyLoaded())
        .put("initiallyLoadedModelCount", preflight.initiallyLoadedModelCount())
        .put("networkTarget", "127.0.0.1:11435")
        .put("requestMode", "SEQUENTIAL_NO_RETRY_NO_TOOLS");

    ObjectNode quality = report.putObject("quality");
    quality.set("fake", qualityAggregates(evaluation.fakeCases()));
    quality.set("liquidAi", qualityAggregates(evaluation.liquidCases()));

    int failureTotal = evaluation.failures().values().stream().mapToInt(Integer::intValue).sum();
    int overlappingFailureObservations =
        Math.max(0, failureTotal - evaluation.uniqueFailedCaseCount());
    int semanticIrFirstViolationCount =
        evaluation.semanticIrFirstViolations().values().stream().mapToInt(Integer::intValue).sum();
    require(
        semanticIrFirstViolationCount
            == evaluation.failures().getOrDefault("SEMANTIC_IR_INVALID", 0),
        "Semantic IR diagnostic arithmetic is inconsistent.");
    require(
        evaluation.semanticIrValidCount() + semanticIrFirstViolationCount
            == evaluation.inferenceSchemaValidCount(),
        "Semantic IR validity arithmetic is inconsistent.");
    ObjectNode failures = report.putObject("failureCounts");
    failures
        .put("total", failureTotal)
        .put("failureObservationCount", failureTotal)
        .put("uniqueFailedCaseCount", evaluation.uniqueFailedCaseCount())
        .put("overlappingFailureObservationCount", overlappingFailureObservations);
    ObjectNode failuresByReason = failures.putObject("byReason");
    evaluation.failures().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> failuresByReason.put(entry.getKey(), entry.getValue()));
    ObjectNode semanticDiagnostics = failures.putObject("semanticIrFirstViolation");
    semanticDiagnostics
        .put("sampleCount", semanticIrFirstViolationCount)
        .put("classification", "FIRST_VIOLATION_BOUNDED_ENUM_ONLY");
    ObjectNode semanticByCode = semanticDiagnostics.putObject("byCode");
    for (SemanticIrFailureCode code : SemanticIrFailureCode.values()) {
      semanticByCode.put(code.name(), evaluation.semanticIrFirstViolations().getOrDefault(code, 0));
    }

    ObjectNode performance = report.putObject("performance");
    performance.set("fakeWallLatency", latencySummary(evaluation.fakeLatencyNanos()));
    performance.set(
        "liquidAiAllAttemptWallLatency", latencySummary(evaluation.liquidAttemptLatencyNanos()));
    performance.set(
        "liquidAiSuccessfulResponseWallLatency",
        latencySummary(evaluation.liquidSuccessfulResponseLatencyNanos()));
    performance
        .putObject("warmup")
        .put("wallMilliseconds", milliseconds(warmup.wallDurationNanos()))
        .set("ollamaApi", apiMetrics(List.of(warmup.metrics())));
    performance.set("scoredOllamaApi", apiMetrics(evaluation.liquidApiMetrics()));
    int outputSizeSampleCount =
        evaluation.modelOutputSizeBuckets().values().stream().mapToInt(Integer::intValue).sum();
    require(
        outputSizeSampleCount == evaluation.responseCount(),
        "Model output size bucket arithmetic is inconsistent.");
    ObjectNode outputSizes = performance.putObject("modelOutputSizeBuckets");
    outputSizes
        .put("sampleCount", outputSizeSampleCount)
        .put("aggregation", "FIXED_BYTE_BUCKET_COUNTS_ONLY");
    ObjectNode outputSizeCounts = outputSizes.putObject("byBucket");
    for (ModelOutputSizeBucket bucket : ModelOutputSizeBucket.values()) {
      outputSizeCounts.put(
          bucket.name(), evaluation.modelOutputSizeBuckets().getOrDefault(bucket, 0));
    }

    ObjectNode hardware = report.putObject("hardware");
    ObjectNode gpu = hardware.putObject("gpu");
    gpu.put("name", configuration.gpuName())
        .put("driverVersion", configuration.gpuDriver())
        .put("totalMiB", configuration.gpuTotalMiB())
        .put("baselineUsedMiB", configuration.gpuBaselineUsedMiB())
        .put("peakUsedStatus", "NOT_AVAILABLE")
        .putNull("peakUsedMiB")
        .put("utilizationStatus", "NOT_AVAILABLE")
        .putNull("utilizationPercent");
    hardware
        .putObject("ollamaObservedAllocation")
        .put("sizeBytes", observedAllocation.sizeBytes())
        .put("sizeVramBytes", observedAllocation.sizeVramBytes())
        .put("contextLength", observedAllocation.contextLength())
        .put("peakClaim", "NOT_AVAILABLE");

    report
        .putObject("restoration")
        .put("status", cleanup.status())
        .put("restored", cleanup.restored())
        .put("scopedRunnerTemporaryArtifactRemaining", !scopedTemporaryArtifactAbsent)
        .put("scopedRunnerTemporaryArtifact", "V5_REPORT_TMP_ONLY")
        .put("processLifecycle", "EXTERNAL_ORCHESTRATOR");

    AggregateEvaluation liquidAll = EvaluationV2Metrics.aggregate(evaluation.liquidCases());
    boolean acceptanceMet =
        developmentAcceptanceMet(
            evaluation.requestCount(),
            evaluation.responseCount(),
            evaluation.inferenceSchemaValidCount(),
            evaluation.semanticIrValidCount(),
            liquidAll.schemaValidCount(),
            liquidAll.domainValidCount(),
            failureTotal,
            liquidAll.legacyWrongLocalCount(),
            liquidAll.inventedPreciseDateCaseCount(),
            liquidAll.localOverflowCount(),
            liquidAll.missingOverflowSignalCount(),
            liquidAll.unresolvedFieldHallucinationCount(),
            cleanup.restored(),
            scopedTemporaryArtifactAbsent);
    ObjectNode acceptance = report.putObject("developmentAcceptance");
    acceptance
        .put("status", acceptanceMet ? "MET" : "NOT_MET")
        .put("role", "DEVELOPMENT_SET_ONLY_NOT_A_PROVIDER_OR_TRAINING_GATE");
    acceptance
        .putObject("targets")
        .put("scoredRequestCount", EXPECTED_CASE_COUNT)
        .put("completedResponseCount", EXPECTED_CASE_COUNT)
        .put("inferenceSchemaValidCount", EXPECTED_CASE_COUNT)
        .put("semanticIRValidCount", EXPECTED_CASE_COUNT)
        .put("canonicalSchemaValidCount", EXPECTED_CASE_COUNT)
        .put("domainValidCount", EXPECTED_CASE_COUNT)
        .put("failureCount", 0)
        .put("legacyWrongLocalCount", 0)
        .put("inventedPreciseDateCaseCount", 0)
        .put("localOverflowCount", 0)
        .put("missingOverflowSignalCount", 0)
        .put("unresolvedFieldHallucinationCount", 0)
        .put("restored", true)
        .put("scopedRunnerTemporaryArtifactRemaining", false);
    acceptance
        .putObject("observed")
        .put("scoredRequestCount", evaluation.requestCount())
        .put("completedResponseCount", evaluation.responseCount())
        .put("inferenceSchemaValidCount", evaluation.inferenceSchemaValidCount())
        .put("semanticIRValidCount", evaluation.semanticIrValidCount())
        .put("canonicalSchemaValidCount", liquidAll.schemaValidCount())
        .put("domainValidCount", liquidAll.domainValidCount())
        .put("failureCount", failureTotal)
        .put("legacyWrongLocalCount", liquidAll.legacyWrongLocalCount())
        .put("inventedPreciseDateCaseCount", liquidAll.inventedPreciseDateCaseCount())
        .put("localOverflowCount", liquidAll.localOverflowCount())
        .put("missingOverflowSignalCount", liquidAll.missingOverflowSignalCount())
        .put("unresolvedFieldHallucinationCount", liquidAll.unresolvedFieldHallucinationCount())
        .put("restored", cleanup.restored())
        .put("scopedRunnerTemporaryArtifactRemaining", !scopedTemporaryArtifactAbsent);

    boolean contractOrSafetyFailure =
        !evaluation.failures().isEmpty()
            || evaluation.liquidCases().stream()
                .anyMatch(value -> !value.schemaValid() || !value.domainValid())
            || evaluation.liquidCases().stream()
                .anyMatch(
                    value ->
                        value.legacyWrongLocal()
                            || value.dates().inventedPreciseDate()
                            || value.localOverflow()
                            || value.missingOverflowSignal()
                            || value.items().unresolvedFieldHallucinationCount() > 0);
    ArrayNode reasons =
        report
            .putObject("fineTuning")
            .put("decision", "NO_GO_FOR_TRAINING")
            .put("loraDecision", "NO_GO")
            .put("authorization", "NOT_AUTHORIZED")
            .put("execution", "NOT_PERFORMED")
            .put(
                "promptSchemaIterationRecommendation",
                contractOrSafetyFailure ? "RECOMMENDED" : "OPTIONAL")
            .putArray("reasonCodes");
    reasons.add(
        contractOrSafetyFailure
            ? "CONTRACT_OR_SAFETY_FINDINGS_REQUIRE_PROMPT_SCHEMA_ITERATION"
            : "CONTRACT_AND_SAFETY_RESULTS_ARE_NOT_A_TRAINING_GATE");
    reasons.add("PUBLIC_VISIBLE_EVIDENCE_IS_INSUFFICIENT_FOR_TRAINING");
    return report;
  }

  static boolean developmentAcceptanceMet(
      int requestCount,
      int responseCount,
      int inferenceSchemaValidCount,
      int semanticIrValidCount,
      int canonicalSchemaValidCount,
      int domainValidCount,
      int failureCount,
      int legacyWrongLocalCount,
      int inventedPreciseDateCaseCount,
      int localOverflowCount,
      int missingOverflowSignalCount,
      int unresolvedFieldHallucinationCount,
      boolean restored,
      boolean scopedTemporaryArtifactAbsent) {
    return requestCount == EXPECTED_CASE_COUNT
        && responseCount == EXPECTED_CASE_COUNT
        && inferenceSchemaValidCount == EXPECTED_CASE_COUNT
        && semanticIrValidCount == EXPECTED_CASE_COUNT
        && canonicalSchemaValidCount == EXPECTED_CASE_COUNT
        && domainValidCount == EXPECTED_CASE_COUNT
        && failureCount == 0
        && legacyWrongLocalCount == 0
        && inventedPreciseDateCaseCount == 0
        && localOverflowCount == 0
        && missingOverflowSignalCount == 0
        && unresolvedFieldHallucinationCount == 0
        && restored
        && scopedTemporaryArtifactAbsent;
  }

  private ObjectNode qualityAggregates(List<CaseEvaluation> cases) {
    ObjectNode value = json.createObjectNode();
    value.set(
        "regression",
        EvaluationV2Metrics.aggregate(
                cases.stream().filter(result -> result.isSplit("REGRESSION")).toList())
            .toJson(json));
    value.set(
        "visibleChallenge",
        EvaluationV2Metrics.aggregate(
                cases.stream().filter(result -> result.isSplit("VISIBLE_CHALLENGE")).toList())
            .toJson(json));
    value.set("all", EvaluationV2Metrics.aggregate(cases).toJson(json));
    return value;
  }

  private ObjectNode latencySummary(List<Long> nanos) {
    ObjectNode value = json.createObjectNode().put("sampleCount", nanos.size());
    if (nanos.isEmpty()) {
      return value
          .putNull("minimumMilliseconds")
          .putNull("p50Milliseconds")
          .putNull("p95Milliseconds")
          .putNull("maximumMilliseconds")
          .putNull("meanMilliseconds");
    }
    List<Long> sorted = nanos.stream().sorted().toList();
    long sum = nanos.stream().mapToLong(Long::longValue).sum();
    return value
        .put("minimumMilliseconds", milliseconds(sorted.getFirst()))
        .put("p50Milliseconds", milliseconds(percentile(sorted, 0.50)))
        .put("p95Milliseconds", milliseconds(percentile(sorted, 0.95)))
        .put("maximumMilliseconds", milliseconds(sorted.getLast()))
        .put("meanMilliseconds", roundedMilliseconds((double) sum / nanos.size()));
  }

  private ObjectNode apiMetrics(List<OllamaApiMetrics> metrics) {
    ObjectNode value = json.createObjectNode().put("sampleCount", metrics.size());
    putDurationSum(
        value,
        "totalDurationMilliseconds",
        metrics.stream()
            .map(OllamaApiMetrics::totalDurationNanos)
            .filter(Objects::nonNull)
            .toList());
    putDurationSum(
        value,
        "loadDurationMilliseconds",
        metrics.stream()
            .map(OllamaApiMetrics::loadDurationNanos)
            .filter(Objects::nonNull)
            .toList());
    putCountSum(
        value,
        "promptTokenCount",
        metrics.stream().map(OllamaApiMetrics::promptEvalCount).filter(Objects::nonNull).toList());
    putDurationSum(
        value,
        "promptDurationMilliseconds",
        metrics.stream()
            .map(OllamaApiMetrics::promptEvalDurationNanos)
            .filter(Objects::nonNull)
            .toList());
    putCountSum(
        value,
        "generatedTokenCount",
        metrics.stream().map(OllamaApiMetrics::evalCount).filter(Objects::nonNull).toList());
    putDurationSum(
        value,
        "generationDurationMilliseconds",
        metrics.stream()
            .map(OllamaApiMetrics::evalDurationNanos)
            .filter(Objects::nonNull)
            .toList());
    return value;
  }

  private void putDurationSum(ObjectNode target, String field, List<Long> samples) {
    target.put(field + "SampleCount", samples.size());
    if (samples.isEmpty()) {
      target.putNull(field);
      return;
    }
    target.put(field, milliseconds(samples.stream().mapToLong(Long::longValue).sum()));
  }

  private void putCountSum(ObjectNode target, String field, List<Long> samples) {
    target.put(field + "SampleCount", samples.size());
    if (samples.isEmpty()) {
      target.putNull(field);
      return;
    }
    target.put(field, samples.stream().mapToLong(Long::longValue).sum());
  }

  private long percentile(List<Long> sorted, double percentile) {
    int index = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private double milliseconds(long nanos) {
    return roundedMilliseconds(nanos);
  }

  private double roundedMilliseconds(double nanos) {
    return Math.round((nanos / 1_000_000d) * 1_000d) / 1_000d;
  }

  private CleanupResult restoreModelState() {
    try {
      ollama.unload();
      boolean restored = !ollama.allocation().loaded();
      return new CleanupResult(restored, restored ? "RESTORED" : "FAILED_MODEL_STILL_LOADED");
    } catch (RuntimeException exception) {
      return new CleanupResult(false, "FAILED_UNLOAD_OR_VERIFY");
    }
  }

  private OllamaObservedAllocation maximumAllocation(
      OllamaObservedAllocation first, OllamaObservedAllocation second) {
    return new OllamaObservedAllocation(
        first.loaded() || second.loaded(),
        Math.max(first.sizeBytes(), second.sizeBytes()),
        Math.max(first.sizeVramBytes(), second.sizeVramBytes()),
        Math.max(first.contextLength(), second.contextLength()));
  }

  private void verifyPreflight(OllamaModelPreflight value) {
    require(
        OllamaLocalShadowClient.EXPECTED_OLLAMA_VERSION.equals(value.ollamaVersion()),
        "Ollama version changed.");
    require(configuration.model().equals(value.model()), "Ollama model tag changed.");
    require(configuration.modelDigest().equals(value.digest()), "Ollama model digest changed.");
    require("gguf".equals(value.format()), "Ollama model is not GGUF.");
    require("lfm2".equals(value.family()), "Ollama model is not LFM2.");
    require("2.7B".equals(value.parameterSize()), "Ollama model size metadata changed.");
    require("Q8_0".equals(value.quantization()), "Ollama model is not the Q8_0 artifact.");
    require(!value.initiallyLoaded(), "Shadow v5 requires a clean unloaded-model prestate.");
    require(
        value.initiallyLoadedModelCount() == 0,
        "Shadow v5 requires zero initially loaded Ollama models.");
  }

  private PinnedResources verifyPinnedResources() {
    byte[] regression = readResourceBytes(REGRESSION_RESOURCE);
    byte[] challenge = readResourceBytes(CHALLENGE_RESOURCE);
    byte[] caseSchema = readResourceBytes(CASE_SCHEMA_RESOURCE);
    byte[] canonicalProposalSchema = readResourceBytes(CANONICAL_PROPOSAL_SCHEMA_RESOURCE);
    byte[] outputSchema = readResourceBytes(OUTPUT_SCHEMA_RESOURCE);

    verifyPinnedSha256("regression fixture", regression, EXPECTED_REGRESSION_SHA256);
    verifyPinnedSha256("visible challenge fixture", challenge, EXPECTED_CHALLENGE_SHA256);
    verifyPinnedSha256("evaluation case schema", caseSchema, EXPECTED_CASE_SCHEMA_SHA256);
    verifyPinnedSha256(
        "canonical proposal schema",
        canonicalProposalSchema,
        EXPECTED_CANONICAL_PROPOSAL_SCHEMA_SHA256);
    verifyPinnedSha256("inference output schema", outputSchema, EXPECTED_OUTPUT_SCHEMA_SHA256);

    Path repositoryRoot = locateRepositoryRoot();
    verifyRepositoryCopy(
        repositoryRoot.resolve("fixtures/korean-memo-cases.json"),
        regression,
        EXPECTED_REGRESSION_SHA256);
    verifyRepositoryCopy(
        repositoryRoot.resolve("backend/src/test/resources/fixtures/korean-memo-cases.json"),
        regression,
        EXPECTED_REGRESSION_SHA256);
    verifyRepositoryCopy(
        repositoryRoot.resolve("fixtures/korean-memo-challenge-cases.json"),
        challenge,
        EXPECTED_CHALLENGE_SHA256);
    verifyRepositoryCopy(
        repositoryRoot.resolve(
            "backend/src/test/resources/fixtures/korean-memo-challenge-cases.json"),
        challenge,
        EXPECTED_CHALLENGE_SHA256);
    verifyRepositoryCopy(
        repositoryRoot.resolve("contracts/korean-memo-evaluation-case.schema.json"),
        caseSchema,
        EXPECTED_CASE_SCHEMA_SHA256);
    verifyRepositoryCopy(
        repositoryRoot.resolve(
            "backend/src/test/resources/contracts/korean-memo-evaluation-case.schema.json"),
        caseSchema,
        EXPECTED_CASE_SCHEMA_SHA256);
    verifyRepositoryCopy(
        repositoryRoot.resolve("contracts/analysis-proposal.schema.json"),
        canonicalProposalSchema,
        EXPECTED_CANONICAL_PROPOSAL_SCHEMA_SHA256);
    verifyRepositoryCopy(
        repositoryRoot.resolve(
            "backend/src/test/resources/contracts/solo-liquidai-shadow-output.schema.json"),
        outputSchema,
        EXPECTED_OUTPUT_SCHEMA_SHA256);

    return new PinnedResources(
        readJson(regression),
        readJson(challenge),
        readJson(caseSchema),
        readJson(outputSchema),
        new ResourceIntegrity(
            EXPECTED_REGRESSION_SHA256,
            EXPECTED_CHALLENGE_SHA256,
            EXPECTED_CASE_SCHEMA_SHA256,
            EXPECTED_CANONICAL_PROPOSAL_SCHEMA_SHA256,
            EXPECTED_OUTPUT_SCHEMA_SHA256));
  }

  static void verifyPinnedSha256(String label, byte[] encoded, String expectedSha256) {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(encoded, "encoded");
    Objects.requireNonNull(expectedSha256, "expectedSha256");
    require(expectedSha256.equals(sha256(encoded)), "Pinned SHA-256 changed for " + label + ".");
  }

  static String sha256(byte[] encoded) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  private Path locateRepositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    for (int depth = 0; depth < 3 && candidate != null; depth++) {
      if (Files.isRegularFile(candidate.resolve("contracts/analysis-proposal.schema.json"))
          && Files.isRegularFile(candidate.resolve("fixtures/korean-memo-cases.json"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Personal Memo repository root could not be located.");
  }

  private void verifyRepositoryCopy(Path file, byte[] classpathBytes, String expectedSha256) {
    try {
      require(Files.isRegularFile(file), "Pinned repository resource copy is missing.");
      require(Files.size(file) <= MAX_RESOURCE_BYTES, "Pinned repository resource is too large.");
      byte[] repositoryBytes = Files.readAllBytes(file);
      verifyPinnedSha256("repository resource copy", repositoryBytes, expectedSha256);
      require(
          Arrays.equals(classpathBytes, repositoryBytes),
          "Classpath and repository resource bytes differ.");
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Pinned repository resource could not be verified.", exception);
    }
  }

  private List<ShadowFixture> loadFixtures(PinnedResources resources, Schema caseSchema) {
    JsonNode regression = resources.regression();
    JsonNode challenge = resources.challenge();
    require(regression.size() == EXPECTED_SPLIT_SIZE, "Regression fixture count changed.");
    require(challenge.size() == EXPECTED_SPLIT_SIZE, "Challenge fixture count changed.");
    List<ShadowFixture> values = new ArrayList<>(EXPECTED_CASE_COUNT);
    addFixtures(values, regression, "REGRESSION", caseSchema);
    addFixtures(values, challenge, "VISIBLE_CHALLENGE", caseSchema);
    Set<String> ids = new HashSet<>();
    Set<String> memoTexts = new HashSet<>();
    for (ShadowFixture value : values) {
      require(ids.add(value.node().path("id").asText()), "Fixture IDs must be unique.");
      require(
          memoTexts.add(value.node().path("content").asText()), "Fixture memos must be unique.");
    }
    require(values.size() == EXPECTED_CASE_COUNT, "Shadow fixture count changed.");
    return List.copyOf(values);
  }

  private void addFixtures(
      List<ShadowFixture> target, JsonNode values, String split, Schema caseSchema) {
    for (int ordinal = 0; ordinal < values.size(); ordinal++) {
      JsonNode fixture = values.get(ordinal);
      require(caseSchema.validate(fixture).isEmpty(), "Fixture JSON Schema validation failed.");
      require("2".equals(fixture.path("datasetVersion").asText()), "Fixture version changed.");
      require(split.equals(fixture.path("split").asText()), "Fixture split changed.");
      EvaluationV2GoldIntegrity.validate(fixture);
      target.add(new ShadowFixture(fixture, split, ordinal));
    }
  }

  private UUID deterministicMemoId(String split, int ordinal) {
    return UUID.nameUUIDFromBytes(
        (MEMO_ID_PREFIX + split + ":" + ordinal).getBytes(StandardCharsets.UTF_8));
  }

  private byte[] readResourceBytes(String resource) {
    byte[] override = resourceOverrides.get(resource);
    if (override != null) {
      require(override.length <= MAX_RESOURCE_BYTES, "Shadow resource override is too large.");
      return override.clone();
    }
    try (InputStream input = getClass().getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Required shadow resource is missing.");
      }
      byte[] encoded = input.readNBytes(MAX_RESOURCE_BYTES + 1);
      require(encoded.length <= MAX_RESOURCE_BYTES, "Shadow resource is too large.");
      return encoded;
    } catch (IOException exception) {
      throw new IllegalStateException("Required shadow resource could not be read.", exception);
    }
  }

  private static Map<String, byte[]> copyResourceOverrides(Map<String, byte[]> values) {
    Objects.requireNonNull(values, "resourceOverrides");
    Map<String, byte[]> copies = new TreeMap<>();
    values.forEach(
        (resource, encoded) -> {
          require(resource != null && resource.startsWith("/"), "Invalid shadow resource key.");
          copies.put(resource, Objects.requireNonNull(encoded, "resource override").clone());
        });
    return Map.copyOf(copies);
  }

  private JsonNode readJson(byte[] encoded) {
    try {
      return json.readTree(encoded);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Required shadow resource is not valid JSON.", exception);
    }
  }

  private Schema loadSchema(JsonNode schemaNode, boolean failFast) {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).failFast(failFast).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
    try {
      Schema schema =
          registry.getSchema(
              new ByteArrayInputStream(schemaNode.toString().getBytes(StandardCharsets.UTF_8)));
      schema.initializeValidators();
      return schema;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Shadow JSON Schema could not be loaded.", exception);
    }
  }

  private void assertAggregateOnly(
      ObjectNode report, byte[] serialized, List<ShadowFixture> fixtures) {
    for (String forbidden : FORBIDDEN_REPORT_FIELDS) {
      require(report.findValue(forbidden) == null, "Report contains a forbidden field.");
    }
    String text = new String(serialized, StandardCharsets.UTF_8);
    require(!text.contains("\"cases\""), "Report contains case objects.");
    for (ShadowFixture fixture : fixtures) {
      require(!text.contains(fixture.node().path("id").asText()), "Report contains a case ID.");
      require(
          !text.contains(fixture.node().path("content").asText()), "Report contains memo text.");
      JsonNode notes = fixture.node().path("notes");
      if (notes.isTextual() && !notes.asText().isBlank()) {
        require(!text.contains(notes.asText()), "Report contains fixture notes.");
      }
      for (JsonNode date : fixture.node().at("/expectedDates/mentions")) {
        require(!text.contains(date.path("surfaceText").asText()), "Report contains gold text.");
      }
    }
  }

  private void publishAtomically(Path temporaryPath, byte[] serialized) throws IOException {
    Path parent = reportPath.getParent();
    if (parent == null) {
      throw new IllegalStateException("Shadow report parent is missing.");
    }
    Files.createDirectories(parent);
    Files.write(temporaryPath, serialized);
    Files.move(
        temporaryPath,
        reportPath,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  private Path temporaryPath() {
    return reportPath.resolveSibling(reportPath.getFileName() + ".tmp");
  }

  private void deleteOutput(Path target) {
    try {
      Files.deleteIfExists(target);
    } catch (IOException exception) {
      throw new IllegalStateException("Shadow output cleanup failed.", exception);
    }
  }

  private <K> void increment(Map<K, Integer> values, K key) {
    values.merge(key, 1, Integer::sum);
  }

  private boolean isIntegrationStructuralFailure(OllamaShadowFailure failure) {
    return switch (failure) {
      case ENDPOINT_REJECTED,
          REQUEST_TOO_LARGE,
          RESPONSE_TOO_LARGE,
          INTERRUPTED,
          IO_FAILURE,
          HTTP_STATUS,
          CONTENT_TYPE,
          MALFORMED_RESPONSE,
          INVALID_WRAPPER,
          TOOL_CALL_REJECTED,
          VERSION_MISMATCH,
          MODEL_MISMATCH ->
          true;
      case MODEL_OUTPUT_TOO_LARGE,
          TIMEOUT,
          MALFORMED_MODEL_JSON,
          INCOMPLETE_RESPONSE,
          TRUNCATED_RESPONSE ->
          false;
    };
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private enum SlotState {
    VALUE,
    ABSENT,
    UNRESOLVED
  }

  private record ParsedSlot(SlotState state, String value) {}

  private enum DateComponentStatus {
    COMPLETE,
    MISSING_YEAR,
    MISSING_TIME,
    MISSING_YEAR_AND_TIME
  }

  private record ParsedPreciseDate(String value, DateComponentStatus componentStatus) {}

  private record ShadowFixture(JsonNode node, String split, int ordinal) {}

  private record EvaluationRun(
      List<CaseEvaluation> fakeCases,
      List<CaseEvaluation> liquidCases,
      List<Long> fakeLatencyNanos,
      List<Long> liquidAttemptLatencyNanos,
      List<Long> liquidSuccessfulResponseLatencyNanos,
      List<OllamaApiMetrics> liquidApiMetrics,
      Map<String, Integer> failures,
      Map<SemanticIrFailureCode, Integer> semanticIrFirstViolations,
      Map<ModelOutputSizeBucket, Integer> modelOutputSizeBuckets,
      int requestCount,
      int responseCount,
      int inferenceSchemaValidCount,
      int semanticIrValidCount,
      int uniqueFailedCaseCount) {}

  private record ResourceIntegrity(
      String regressionSha256,
      String challengeSha256,
      String caseSchemaSha256,
      String canonicalProposalSchemaSha256,
      String outputSchemaSha256) {}

  private record PinnedResources(
      JsonNode regression,
      JsonNode challenge,
      JsonNode caseSchemaNode,
      JsonNode outputSchemaNode,
      ResourceIntegrity integrity) {
    PinnedResources {
      require(regression.isArray(), "Expected regression fixtures to be an array.");
      require(challenge.isArray(), "Expected challenge fixtures to be an array.");
      require(caseSchemaNode.isObject(), "Expected evaluation case schema to be an object.");
      require(outputSchemaNode.isObject(), "Expected inference output schema to be an object.");
    }
  }

  private record CleanupResult(boolean restored, String status) {
    static CleanupResult notAttempted() {
      return new CleanupResult(false, "NOT_ATTEMPTED");
    }
  }

  record ShadowConfiguration(
      String model,
      String modelDigest,
      String baseHead,
      String sourceBundleSha256,
      String gpuName,
      String gpuDriver,
      long gpuTotalMiB,
      long gpuBaselineUsedMiB) {
    static ShadowConfiguration from(Map<String, String> environment) {
      Objects.requireNonNull(environment, "environment");
      requireExact(environment, OPT_IN_ENV, OPT_IN_VALUE);
      requireExact(environment, MODEL_ENV, EXPECTED_MODEL);
      requireExact(environment, DIGEST_ENV, EXPECTED_DIGEST);
      String baseHead = requirePattern(environment, BASE_HEAD_ENV, "[0-9a-f]{40}|[0-9a-f]{64}");
      String sourceBundle = requirePattern(environment, SOURCE_BUNDLE_ENV, "[0-9a-f]{64}");
      String gpuName = requirePattern(environment, GPU_NAME_ENV, GPU_TEXT.pattern());
      String gpuDriver = requirePattern(environment, GPU_DRIVER_ENV, DRIVER_TEXT.pattern());
      long total = requireMiB(environment, GPU_TOTAL_ENV, false);
      long baselineUsed = requireMiB(environment, GPU_BASELINE_USED_ENV, true);
      if (baselineUsed > total) {
        throw new IllegalArgumentException("GPU baseline usage cannot exceed total memory.");
      }
      return new ShadowConfiguration(
          EXPECTED_MODEL,
          EXPECTED_DIGEST,
          baseHead,
          sourceBundle,
          gpuName,
          gpuDriver,
          total,
          baselineUsed);
    }

    private static void requireExact(
        Map<String, String> environment, String name, String expected) {
      if (!expected.equals(environment.get(name))) {
        throw new IllegalArgumentException(name + " must contain the exact shadow opt-in value.");
      }
    }

    private static String requirePattern(
        Map<String, String> environment, String name, String pattern) {
      String value = environment.get(name);
      if (value == null || !value.matches(pattern)) {
        throw new IllegalArgumentException(name + " is missing or outside its allowlist.");
      }
      return value;
    }

    private static long requireMiB(
        Map<String, String> environment, String name, boolean zeroAllowed) {
      String value = environment.get(name);
      if (value == null || !value.matches("[0-9]{1,7}")) {
        throw new IllegalArgumentException(name + " must be a bounded integer MiB value.");
      }
      long parsed = Long.parseLong(value);
      long minimum = zeroAllowed ? 0 : 1;
      if (parsed < minimum || parsed > 1_048_576) {
        throw new IllegalArgumentException(name + " is outside the supported MiB range.");
      }
      return parsed;
    }
  }
}

enum SemanticIrFailureCode {
  SHAPE_INVALID,
  TEXT_INVALID,
  ARRAY_BOUND_EXCEEDED,
  ITEM_COVERAGE_CONTRADICTION,
  DATE_COVERAGE_CONTRADICTION,
  TYPE_CONTRADICTION,
  TOPIC_CONTRADICTION,
  SLOT_ENCODING_INVALID,
  NON_TASK_ACTION_CONTRADICTION,
  DATE_CONTRADICTION,
  REVIEW_FLAG_CONTRADICTION,
  UNEXPECTED_ASSEMBLY_FAILURE
}

final class SemanticIrException extends IllegalStateException {
  private final SemanticIrFailureCode code;

  SemanticIrException(SemanticIrFailureCode code) {
    super("Semantic IR validation failed.");
    this.code = Objects.requireNonNull(code, "code");
  }

  SemanticIrFailureCode code() {
    return code;
  }
}

enum ModelOutputSizeBucket {
  BYTES_0_TO_1024,
  BYTES_1025_TO_4096,
  BYTES_4097_TO_16384,
  BYTES_16385_TO_65536;

  static ModelOutputSizeBucket forBytes(int bytes) {
    if (bytes < 0 || bytes > 65_536) {
      throw new IllegalArgumentException("Model output byte count is outside the bounded range.");
    }
    if (bytes <= 1_024) {
      return BYTES_0_TO_1024;
    }
    if (bytes <= 4_096) {
      return BYTES_1025_TO_4096;
    }
    if (bytes <= 16_384) {
      return BYTES_4097_TO_16384;
    }
    return BYTES_16385_TO_65536;
  }
}

/** Single-constructor JUnit entry point selected only by the explicit Maven runner name. */
class SoloLiquidAiShadowBaselineRunner extends SoloLiquidAiShadowBaselineCore {
  SoloLiquidAiShadowBaselineRunner() {
    super();
  }
}
