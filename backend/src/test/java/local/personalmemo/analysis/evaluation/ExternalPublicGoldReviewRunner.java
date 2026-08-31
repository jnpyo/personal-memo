package local.personalmemo.analysis.evaluation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Explicit local runner for two independently authored public-v2 review manifests. Its class name
 * deliberately does not match Surefire's default test-name patterns.
 */
class ExternalPublicGoldReviewRunner {
  static final String FIRST_MANIFEST_ENVIRONMENT = "PERSONAL_MEMO_PUBLIC_REVIEW_MANIFEST_A";
  static final String SECOND_MANIFEST_ENVIRONMENT = "PERSONAL_MEMO_PUBLIC_REVIEW_MANIFEST_B";
  static final String COMMIT_ENVIRONMENT = "PERSONAL_MEMO_CANDIDATE_COMMIT";
  static final long MAXIMUM_MANIFEST_BYTES = 4L * 1024 * 1024;

  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
  private static final String CASE_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-case.schema.json";
  private static final String REVIEW_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-review.schema.json";
  private static final int MAXIMUM_PUBLIC_RESOURCE_BYTES = 32 * 1024 * 1024;
  private static final int MAXIMUM_PROCESS_OUTPUT_BYTES = 2 * 1024 * 1024;
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
  private static final Set<String> SUMMARY_FIELDS =
      Set.of(
          "summaryVersion",
          "status",
          "containsRawMemoContent",
          "reviewerCount",
          "caseCount",
          "fieldComparisonCount",
          "agreementCount",
          "disagreementCount",
          "acceptedByBothCount",
          "changeRequiredByEitherCount",
          "automaticResolutionApplied");
  private static final Set<String> SUMMARY_STATUSES =
      Set.of("CONSENSUS_ACCEPTED", "NEEDS_HUMAN_RESOLUTION");
  private final ObjectMapper json = strictJsonMapper();

  @Test
  void writesAggregateOnlySummaryForPinnedExternalPublicReviews() throws Throwable {
    Path repository = repositoryRoot();
    Path report = reportPath(repository);
    Path temporaryReport = temporaryReportPath(repository);
    runWithOutputCleanup(
        repository,
        report,
        temporaryReport,
        () -> runFromEnvironment(repository, report, temporaryReport));
  }

  private void runFromEnvironment(Path repository, Path report, Path temporaryReport) {
    RunnerConfiguration configuration =
        RunnerConfiguration.from(
            Map.of(
                FIRST_MANIFEST_ENVIRONMENT,
                environment(FIRST_MANIFEST_ENVIRONMENT),
                SECOND_MANIFEST_ENVIRONMENT,
                environment(SECOND_MANIFEST_ENVIRONMENT),
                COMMIT_ENVIRONMENT,
                environment(COMMIT_ENVIRONMENT)));
    verifyPinnedCleanCommit(repository, configuration.candidateCommit());

    ManifestPaths manifests =
        validateExternalManifestPaths(
            repository, configuration.firstManifest(), configuration.secondManifest());
    PublicInputs publicInputs = loadSynchronizedPublicInputs(repository);
    JsonNode firstReview = readStrictJson(manifests.first(), MAXIMUM_MANIFEST_BYTES);
    JsonNode secondReview = readStrictJson(manifests.second(), MAXIMUM_MANIFEST_BYTES);

    ObjectNode summary;
    try {
      summary =
          new PublicGoldAdjudicationVerifier(json)
              .verify(
                  publicInputs.regression(),
                  publicInputs.visibleChallenge(),
                  firstReview,
                  secondReview);
    } catch (RuntimeException exception) {
      throw failure("The public review manifests could not be verified.");
    }

    assertAggregateOnlySummary(summary, publicInputs.release().caseIds().size());

    // Source and worktree pinning is checked again immediately before the only persistent write.
    verifyPinnedCleanCommit(repository, configuration.candidateCommit());
    writeAtomically(repository, report, temporaryReport, summary, json);
  }

  static ObjectMapper strictJsonMapper() {
    return JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
  }

  static ManifestPaths validateExternalManifestPaths(
      Path repository, Path firstConfigured, Path secondConfigured) {
    Path first = validateExternalManifestPath(repository, firstConfigured);
    Path second = validateExternalManifestPath(repository, secondConfigured);
    try {
      if (first.equals(second) || Files.isSameFile(first, second)) {
        fail("The public review manifests must be distinct files.");
      }
    } catch (IOException | SecurityException exception) {
      throw failure("The public review manifest identities could not be validated.");
    }
    return new ManifestPaths(first, second);
  }

  private static Path validateExternalManifestPath(Path repository, Path configured) {
    try {
      if (configured == null || !configured.isAbsolute()) {
        fail("A public review manifest path must be absolute.");
      }
      Path normalized = configured.normalize();
      Path root = normalized.getRoot();
      if (root == null) {
        fail("A public review manifest path must be absolute.");
      }
      Path cursor = root;
      for (Path part : normalized) {
        cursor = cursor.resolve(part);
        rejectLinkLikeComponent(cursor);
      }

      Path realRepository = repository.toRealPath();
      Path realManifest = normalized.toRealPath();
      long size = Files.size(realManifest);
      if (!Files.isRegularFile(realManifest, LinkOption.NOFOLLOW_LINKS)
          || realManifest.startsWith(realRepository)
          || size <= 0
          || size > MAXIMUM_MANIFEST_BYTES) {
        fail("A public review manifest is outside the allowed file boundary.");
      }
      return realManifest;
    } catch (IOException | SecurityException exception) {
      throw failure("A public review manifest path could not be validated.");
    }
  }

  private static void rejectLinkLikeComponent(Path component) throws IOException {
    if (Files.isSymbolicLink(component)) {
      fail("Symbolic or reparse links are not allowed in public review paths.");
    }
    Path followed = component.toRealPath();
    Path notFollowed = component.toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (!followed.equals(notFollowed)) {
      fail("Symbolic or reparse links are not allowed in public review paths.");
    }
  }

  static JsonNode readStrictJson(Path path, long maximumBytes) {
    try {
      Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
      try (InputStream input =
          java.nio.channels.Channels.newInputStream(Files.newByteChannel(path, options))) {
        return parseStrictJson(readBounded(input, maximumBytes));
      }
    } catch (IOException | RuntimeException exception) {
      throw failure("A public review manifest could not be read as strict UTF-8 JSON.");
    }
  }

  private static JsonNode readStrictJsonResource(String resource) {
    try (InputStream input = ExternalPublicGoldReviewRunner.class.getResourceAsStream(resource)) {
      if (input == null) {
        fail("A synchronized public review resource is missing.");
      }
      return parseStrictJson(readBounded(input, MAXIMUM_PUBLIC_RESOURCE_BYTES));
    } catch (IOException | RuntimeException exception) {
      throw failure("A synchronized public review resource could not be read.");
    }
  }

  static JsonNode readStrictRepositoryJson(Path repository, Path relativePath) {
    try {
      Path realRepository = repository.toRealPath();
      if (relativePath == null || relativePath.isAbsolute()) {
        fail("A synchronized repository review resource path is invalid.");
      }

      Path cursor = realRepository;
      for (Path component : relativePath.normalize()) {
        if ("..".equals(component.toString())) {
          fail("A synchronized repository review resource path is invalid.");
        }
        cursor = cursor.resolve(component);
        rejectLinkLikeComponent(cursor);
      }

      Path realResource = cursor.toRealPath(LinkOption.NOFOLLOW_LINKS);
      long size = Files.size(realResource);
      if (!realResource.startsWith(realRepository)
          || !Files.isRegularFile(realResource, LinkOption.NOFOLLOW_LINKS)
          || size <= 0
          || size > MAXIMUM_PUBLIC_RESOURCE_BYTES) {
        fail("A synchronized repository review resource is outside the allowed file boundary.");
      }

      Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
      try (InputStream input =
          java.nio.channels.Channels.newInputStream(Files.newByteChannel(realResource, options))) {
        return parseStrictJson(readBounded(input, MAXIMUM_PUBLIC_RESOURCE_BYTES));
      }
    } catch (IOException | RuntimeException exception) {
      throw failure("A synchronized repository review resource could not be read.");
    }
  }

  private static JsonNode parseStrictJson(byte[] bytes) {
    if (hasByteOrderMark(bytes)) {
      fail("Byte-order marks are not allowed in public review JSON.");
    }
    String decoded;
    try {
      CharBuffer characters =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes));
      decoded = characters.toString();
    } catch (CharacterCodingException exception) {
      throw failure("Public review JSON is not strict UTF-8.");
    }
    try {
      return strictJsonMapper().readTree(decoded);
    } catch (RuntimeException exception) {
      throw failure("Public review JSON is malformed or contains duplicate keys.");
    }
  }

  private static boolean hasByteOrderMark(byte[] bytes) {
    return startsWith(bytes, 0xef, 0xbb, 0xbf)
        || startsWith(bytes, 0xfe, 0xff)
        || startsWith(bytes, 0xff, 0xfe)
        || startsWith(bytes, 0x00, 0x00, 0xfe, 0xff)
        || startsWith(bytes, 0xff, 0xfe, 0x00, 0x00);
  }

  private static boolean startsWith(byte[] bytes, int... prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if (Byte.toUnsignedInt(bytes[index]) != prefix[index]) {
        return false;
      }
    }
    return true;
  }

  private static byte[] readBounded(InputStream input, long maximumBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if ((long) output.size() + read > maximumBytes) {
        throw new IOException("Input limit exceeded.");
      }
      output.write(buffer, 0, read);
    }
    if (output.size() == 0) {
      throw new IOException("Empty input.");
    }
    return output.toByteArray();
  }

  private PublicInputs loadSynchronizedPublicInputs(Path repository) {
    JsonNode bundledRegression = readStrictJsonResource(REGRESSION_RESOURCE);
    JsonNode bundledChallenge = readStrictJsonResource(CHALLENGE_RESOURCE);
    JsonNode bundledCaseSchema = readStrictJsonResource(CASE_SCHEMA_RESOURCE);
    JsonNode bundledReviewSchema = readStrictJsonResource(REVIEW_SCHEMA_RESOURCE);

    JsonNode repositoryRegression =
        readStrictRepositoryJson(repository, Path.of("fixtures/korean-memo-cases.json"));
    JsonNode repositoryChallenge =
        readStrictRepositoryJson(repository, Path.of("fixtures/korean-memo-challenge-cases.json"));
    JsonNode repositoryCaseSchema =
        readStrictRepositoryJson(
            repository, Path.of("contracts/korean-memo-evaluation-case.schema.json"));
    JsonNode repositoryReviewSchema =
        readStrictRepositoryJson(
            repository, Path.of("contracts/korean-memo-evaluation-review.schema.json"));

    requireSynchronized(
        bundledRegression,
        repositoryRegression,
        "The regression fixture resource is not synchronized.");
    requireSynchronized(
        bundledChallenge,
        repositoryChallenge,
        "The visible-challenge fixture resource is not synchronized.");
    requireSynchronized(
        bundledCaseSchema,
        repositoryCaseSchema,
        "The evaluation case schema resource is not synchronized.");
    requireSynchronized(
        bundledReviewSchema,
        repositoryReviewSchema,
        "The evaluation review schema resource is not synchronized.");

    PublicEvaluationRelease release;
    try {
      release = PublicEvaluationRelease.from(json, bundledRegression, bundledChallenge);
    } catch (RuntimeException exception) {
      throw failure("The synchronized public evaluation release is invalid.");
    }
    return new PublicInputs(bundledRegression, bundledChallenge, release);
  }

  static void requireSynchronized(JsonNode bundled, JsonNode repository, String message) {
    if (bundled == null || !bundled.equals(repository)) {
      fail(message);
    }
  }

  static void assertAggregateOnlySummary(JsonNode summary, int expectedCaseCount) {
    assertExactFields(summary, SUMMARY_FIELDS);
    String status = summary.path("status").asText();
    int fieldComparisonCount = summary.path("fieldComparisonCount").asInt(-1);
    int agreementCount = summary.path("agreementCount").asInt(-1);
    int disagreementCount = summary.path("disagreementCount").asInt(-1);
    int acceptedByBothCount = summary.path("acceptedByBothCount").asInt(-1);
    int changeRequiredByEitherCount = summary.path("changeRequiredByEitherCount").asInt(-1);
    boolean consensusAccepted = "CONSENSUS_ACCEPTED".equals(status);
    if (!summary.path("summaryVersion").isTextual()
        || !"1".equals(summary.path("summaryVersion").asText())
        || !summary.path("status").isTextual()
        || !SUMMARY_STATUSES.contains(status)
        || !summary.path("containsRawMemoContent").isBoolean()
        || summary.path("containsRawMemoContent").asBoolean(true)
        || !summary.path("reviewerCount").isIntegralNumber()
        || !summary.path("reviewerCount").canConvertToInt()
        || summary.path("reviewerCount").asInt(-1) != 2
        || !summary.path("caseCount").isIntegralNumber()
        || !summary.path("caseCount").canConvertToInt()
        || summary.path("caseCount").asInt(-1) != expectedCaseCount
        || !summary.path("fieldComparisonCount").isIntegralNumber()
        || !summary.path("fieldComparisonCount").canConvertToInt()
        || fieldComparisonCount != Math.multiplyExact(expectedCaseCount, 3)
        || !summary.path("agreementCount").isIntegralNumber()
        || !summary.path("agreementCount").canConvertToInt()
        || agreementCount < 0
        || !summary.path("disagreementCount").isIntegralNumber()
        || !summary.path("disagreementCount").canConvertToInt()
        || disagreementCount < 0
        || agreementCount + disagreementCount != fieldComparisonCount
        || !summary.path("acceptedByBothCount").isIntegralNumber()
        || !summary.path("acceptedByBothCount").canConvertToInt()
        || acceptedByBothCount < 0
        || !summary.path("changeRequiredByEitherCount").isIntegralNumber()
        || !summary.path("changeRequiredByEitherCount").canConvertToInt()
        || changeRequiredByEitherCount < 0
        || acceptedByBothCount + changeRequiredByEitherCount != fieldComparisonCount
        || acceptedByBothCount > agreementCount
        || !summary.path("automaticResolutionApplied").isBoolean()
        || summary.path("automaticResolutionApplied").asBoolean(true)) {
      fail("The public review summary boundary is invalid.");
    }
    if (consensusAccepted
        && (disagreementCount != 0
            || changeRequiredByEitherCount != 0
            || agreementCount != fieldComparisonCount
            || acceptedByBothCount != fieldComparisonCount)) {
      fail("The public review summary status is inconsistent with its aggregate counts.");
    }
    if (!consensusAccepted && disagreementCount == 0 && changeRequiredByEitherCount == 0) {
      fail("The public review summary status is inconsistent with its aggregate counts.");
    }

    Set<String> textValues = new HashSet<>();
    collectTextValues(summary, textValues);
    if (!textValues.equals(Set.of("1", status))) {
      fail("The public review summary contains non-aggregate information.");
    }
  }

  private static void assertExactFields(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject()) {
      fail("The public review summary shape is invalid.");
    }
    Set<String> actual = new HashSet<>();
    actual.addAll(value.propertyNames());
    if (!actual.equals(expected)) {
      fail("The public review summary shape is invalid.");
    }
  }

  private static void collectTextValues(JsonNode value, Set<String> values) {
    if (value.isTextual()) {
      values.add(value.asText());
    } else if (value.isObject() || value.isArray()) {
      for (JsonNode child : value) {
        collectTextValues(child, values);
      }
    }
  }

  private void verifyPinnedCleanCommit(Path repository, String candidateCommit) {
    String head =
        new String(
                runGit(repository, "rev-parse", "--verify", "HEAD^{commit}"),
                StandardCharsets.UTF_8)
            .strip();
    byte[] status =
        runGit(
            repository,
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
            "--ignore-submodules=none");
    assertPinnedCleanCommit(candidateCommit, head, status);
  }

  static void assertPinnedCleanCommit(String candidateCommit, String head, byte[] status) {
    if (candidateCommit == null
        || !COMMIT.matcher(candidateCommit).matches()
        || head == null
        || !COMMIT.matcher(head).matches()
        || !head.equals(candidateCommit)) {
      fail("The candidate commit does not match the exact current HEAD.");
    }
    if (status == null || status.length != 0) {
      fail("The candidate worktree must be clean.");
    }
  }

  private byte[] runGit(Path repository, String... arguments) {
    Process process = null;
    ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
    Future<byte[]> outputFuture = null;
    try {
      String[] command = new String[arguments.length + 3];
      command[0] = "git";
      command[1] = "-C";
      command[2] = repository.toString();
      System.arraycopy(arguments, 0, command, 3, arguments.length);
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      Process startedProcess = process;
      outputFuture =
          readerExecutor.submit(() -> readProcessOutput(startedProcess.getInputStream()));
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        fail("Git state could not be verified.");
      }
      byte[] output = outputFuture.get(1, TimeUnit.SECONDS);
      if (process.exitValue() != 0) {
        fail("Git state could not be verified.");
      }
      return output;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw failure("Git state verification was interrupted.");
    } catch (ExecutionException | TimeoutException | IOException | RuntimeException exception) {
      throw failure("Git state could not be verified.");
    } finally {
      if (outputFuture != null) {
        outputFuture.cancel(true);
      }
      readerExecutor.shutdownNow();
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static byte[] readProcessOutput(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (output.size() + read > MAXIMUM_PROCESS_OUTPUT_BYTES) {
        throw new IOException("Process output limit exceeded.");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  static void runWithOutputCleanup(
      Path repository, Path report, Path temporaryReport, ThrowingOperation operation)
      throws Throwable {
    deleteOutputs(repository, report, temporaryReport);
    try {
      operation.run();
      if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
          || Files.exists(temporaryReport, LinkOption.NOFOLLOW_LINKS)) {
        fail("The public review summary was not finalized atomically.");
      }
    } catch (Throwable failure) {
      try {
        deleteOutputs(repository, report, temporaryReport);
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  static void writeAtomically(
      Path repository, Path report, Path temporaryReport, JsonNode value, ObjectMapper json) {
    try {
      validateOutputBoundary(repository, report, temporaryReport);
      Files.createDirectories(report.getParent());
      byte[] serialized = serializeSummary(value, json);
      Files.write(
          temporaryReport, serialized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      validateOutputBoundary(repository, report, temporaryReport);
      Files.move(temporaryReport, report, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException exception) {
      throw failure("The public review summary could not be written atomically.");
    }
  }

  static byte[] serializeSummary(JsonNode value, ObjectMapper json) {
    try {
      return (json.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException exception) {
      throw failure("The public review summary could not be serialized.");
    }
  }

  private static void deleteOutputs(Path repository, Path... paths) {
    try {
      validateOutputBoundary(repository, paths[0], paths[1]);
      for (Path path : paths) {
        Files.deleteIfExists(path);
      }
    } catch (IOException | RuntimeException exception) {
      throw failure("A stale public review summary could not be removed.");
    }
  }

  static void validateOutputBoundary(Path repository, Path report, Path temporaryReport) {
    try {
      Path realRepository = repository.toRealPath();
      Path realBackend = realRepository.resolve("backend").toRealPath();
      Path expectedReport =
          realBackend.resolve("target/evaluation/public-v2-review-summary.json").normalize();
      Path expectedTemporary =
          realBackend.resolve("target/evaluation/public-v2-review-summary.json.tmp").normalize();
      if (!report.toAbsolutePath().normalize().equals(expectedReport)
          || !temporaryReport.toAbsolutePath().normalize().equals(expectedTemporary)) {
        fail("The public review summary output boundary is invalid.");
      }

      Path cursor = realBackend;
      for (String component : List.of("target", "evaluation")) {
        cursor = cursor.resolve(component);
        if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
          rejectLinkLikeComponent(cursor);
          if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
            fail("The public review summary output boundary is invalid.");
          }
        } else {
          break;
        }
      }
      Path nearestExistingParent = expectedReport.getParent();
      while (nearestExistingParent != null
          && !Files.exists(nearestExistingParent, LinkOption.NOFOLLOW_LINKS)) {
        nearestExistingParent = nearestExistingParent.getParent();
      }
      if (nearestExistingParent == null
          || !nearestExistingParent.toRealPath().startsWith(realBackend)) {
        fail("The public review summary output boundary is invalid.");
      }
    } catch (IOException | SecurityException exception) {
      throw failure("The public review summary output boundary could not be validated.");
    }
  }

  private Path repositoryRoot() {
    Path current = Path.of(System.getProperty("basedir", "")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve(".git"))
          && Files.isRegularFile(current.resolve("backend/pom.xml"))) {
        try {
          return current.toRealPath();
        } catch (IOException exception) {
          fail("The repository path could not be resolved.");
        }
      }
      current = current.getParent();
    }
    throw failure("The repository root could not be found.");
  }

  private static Path reportPath(Path repository) {
    return repository.resolve("backend/target/evaluation/public-v2-review-summary.json");
  }

  private static Path temporaryReportPath(Path repository) {
    return reportPath(repository).resolveSibling("public-v2-review-summary.json.tmp");
  }

  private static String environment(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value;
  }

  private static IllegalStateException failure(String message) {
    return new IllegalStateException(message);
  }

  private static void fail(String message) {
    throw failure(message);
  }

  record RunnerConfiguration(Path firstManifest, Path secondManifest, String candidateCommit) {
    static RunnerConfiguration from(Map<String, String> environment) {
      String first = required(environment, FIRST_MANIFEST_ENVIRONMENT);
      String second = required(environment, SECOND_MANIFEST_ENVIRONMENT);
      String commit = required(environment, COMMIT_ENVIRONMENT);
      if (!commit.equals(commit.strip()) || !COMMIT.matcher(commit).matches()) {
        fail("The candidate commit is invalid.");
      }
      try {
        return new RunnerConfiguration(Path.of(first), Path.of(second), commit);
      } catch (RuntimeException exception) {
        throw failure("A public review manifest path is invalid.");
      }
    }

    private static String required(Map<String, String> environment, String name) {
      String value = environment.get(name);
      if (value == null || value.isBlank()) {
        fail("Required public-review runner environment is missing.");
      }
      return value;
    }
  }

  record ManifestPaths(Path first, Path second) {}

  private record PublicInputs(
      JsonNode regression, JsonNode visibleChallenge, PublicEvaluationRelease release) {}

  @FunctionalInterface
  interface ThrowingOperation {
    void run() throws Throwable;
  }
}
