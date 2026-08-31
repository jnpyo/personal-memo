package local.personalmemo.analysis.evaluation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
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

/** Explicit-only runner for the deterministic public v2 date/item gold review packet. */
class PublicGoldReviewPacketRunner {
  static final int MAX_FIXTURE_BYTES = 1_048_576;
  static final int MAX_SCHEMA_BYTES = 262_144;
  static final String CANDIDATE_COMMIT_ENV = "PERSONAL_MEMO_CANDIDATE_COMMIT";

  private static final int MAX_GIT_OUTPUT_BYTES = 65_536;
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

  private static final Path REGRESSION_PATH = Path.of("fixtures", "korean-memo-cases.json");
  private static final Path CHALLENGE_PATH =
      Path.of("fixtures", "korean-memo-challenge-cases.json");
  private static final Path CASE_SCHEMA_PATH =
      Path.of("contracts", "korean-memo-evaluation-case.schema.json");
  private static final Path TEST_REGRESSION_PATH =
      Path.of("src", "test", "resources", "fixtures", "korean-memo-cases.json");
  private static final Path TEST_CHALLENGE_PATH =
      Path.of("src", "test", "resources", "fixtures", "korean-memo-challenge-cases.json");
  private static final Path TEST_CASE_SCHEMA_PATH =
      Path.of("src", "test", "resources", "contracts", "korean-memo-evaluation-case.schema.json");
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
  private static final String CASE_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-case.schema.json";
  private static final String OUTPUT_FILE = "public-v2-review-packet.html";

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @Test
  void writePublicV2ReviewPacket() throws Exception {
    Path backendRoot = Path.of("").toAbsolutePath().normalize();
    requireBackendRoot(backendRoot);
    Path repositoryRoot = Objects.requireNonNull(backendRoot.getParent(), "repositoryRoot");
    String candidateCommit = System.getenv(CANDIDATE_COMMIT_ENV);
    prepareRun(backendRoot, () -> verifyPinnedCleanCommit(repositoryRoot, candidateCommit));

    JsonNode repositoryRegression = readJson(repositoryRoot, REGRESSION_PATH, MAX_FIXTURE_BYTES);
    JsonNode repositoryChallenge = readJson(repositoryRoot, CHALLENGE_PATH, MAX_FIXTURE_BYTES);
    JsonNode repositorySchema = readJson(repositoryRoot, CASE_SCHEMA_PATH, MAX_SCHEMA_BYTES);

    JsonNode sourceRegression = readJson(backendRoot, TEST_REGRESSION_PATH, MAX_FIXTURE_BYTES);
    JsonNode sourceChallenge = readJson(backendRoot, TEST_CHALLENGE_PATH, MAX_FIXTURE_BYTES);
    JsonNode sourceSchema = readJson(backendRoot, TEST_CASE_SCHEMA_PATH, MAX_SCHEMA_BYTES);

    JsonNode bundledRegression = readResource(REGRESSION_RESOURCE, MAX_FIXTURE_BYTES);
    JsonNode bundledChallenge = readResource(CHALLENGE_RESOURCE, MAX_FIXTURE_BYTES);
    JsonNode bundledSchema = readResource(CASE_SCHEMA_RESOURCE, MAX_SCHEMA_BYTES);

    requireMirror(repositoryRegression, sourceRegression, bundledRegression);
    requireMirror(repositoryChallenge, sourceChallenge, bundledChallenge);
    requireMirror(repositorySchema, sourceSchema, bundledSchema);

    byte[] packet =
        new PublicGoldReviewPacketRenderer(JSON).render(repositoryRegression, repositoryChallenge);
    writePacket(
        backendRoot, packet, () -> verifyPinnedCleanCommit(repositoryRoot, candidateCommit));
  }

  static byte[] readRegularUtf8(Path root, Path relative, int maxBytes) throws IOException {
    if (maxBytes < 1) {
      throw new IllegalArgumentException("The input bound is invalid.");
    }
    Path file = requireRegularFileWithoutLinks(root, relative);
    long declaredSize = Files.size(file);
    if (declaredSize < 1 || declaredSize > maxBytes) {
      throw new IllegalArgumentException("A required public evaluation file has an invalid size.");
    }
    byte[] bytes;
    try (InputStream input =
        Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(maxBytes + 1);
      if (bytes.length > maxBytes || input.read() != -1) {
        throw new IllegalArgumentException("A required public evaluation file exceeds its bound.");
      }
    }
    strictUtf8(bytes);
    return bytes;
  }

  static Path requireSafeOutputDirectory(Path backendRoot) throws IOException {
    Path normalizedBackend = backendRoot.toAbsolutePath().normalize();
    Path realBackend = normalizedBackend.toRealPath();
    Path target = requireOrCreateDirectory(normalizedBackend.resolve("target"), realBackend);
    return requireOrCreateDirectory(target.resolve("evaluation"), realBackend);
  }

  static void prepareRun(Path backendRoot, Runnable verifyBeforeRead) throws IOException {
    Objects.requireNonNull(verifyBeforeRead, "verifyBeforeRead");
    Path outputDirectory = requireSafeOutputDirectory(backendRoot);
    Path output = outputDirectory.resolve(OUTPUT_FILE);
    requireSafeOutputDestination(output);
    Files.deleteIfExists(output);
    verifyBeforeRead.run();
  }

  private static JsonNode readJson(Path root, Path relative, int maxBytes) throws IOException {
    return parseStrictJson(readRegularUtf8(root, relative, maxBytes));
  }

  private JsonNode readResource(String resource, int maxBytes) throws IOException {
    byte[] bytes;
    try (InputStream input = getClass().getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("A bundled public evaluation resource is missing.");
      }
      bytes = input.readNBytes(maxBytes + 1);
      if (bytes.length == 0 || bytes.length > maxBytes || input.read() != -1) {
        throw new IllegalArgumentException(
            "A bundled public evaluation resource has an invalid size.");
      }
    }
    return parseStrictJson(bytes);
  }

  static JsonNode parseStrictJson(byte[] bytes) {
    try {
      return JSON.readTree(strictUtf8(bytes));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "A public evaluation resource is not valid JSON.", exception);
    }
  }

  private static String strictUtf8(byte[] bytes) {
    if (bytes.length >= 3
        && (bytes[0] & 0xff) == 0xef
        && (bytes[1] & 0xff) == 0xbb
        && (bytes[2] & 0xff) == 0xbf) {
      throw new IllegalArgumentException(
          "UTF-8 BOM is not allowed in a public evaluation resource.");
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException(
          "A public evaluation resource is not strict UTF-8.", exception);
    }
  }

  private static Path requireRegularFileWithoutLinks(Path root, Path relative) throws IOException {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path realRoot = normalizedRoot.toRealPath();
    Path normalizedRelative = relative.normalize();
    if (normalizedRelative.isAbsolute()
        || normalizedRelative.getNameCount() == 0
        || normalizedRelative.startsWith("..")) {
      throw new IllegalArgumentException("A required public evaluation path is invalid.");
    }

    Path current = normalizedRoot;
    for (int index = 0; index < normalizedRelative.getNameCount(); index++) {
      current = current.resolve(normalizedRelative.getName(index));
      BasicFileAttributes attributes =
          Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (Files.isSymbolicLink(current) || attributes.isSymbolicLink() || attributes.isOther()) {
        throw new IllegalArgumentException(
            "A required public evaluation path contains a link or reparse point.");
      }
      boolean finalComponent = index == normalizedRelative.getNameCount() - 1;
      if (finalComponent ? !attributes.isRegularFile() : !attributes.isDirectory()) {
        throw new IllegalArgumentException(
            "A required public evaluation path has an invalid file type.");
      }
      Path followed = current.toRealPath();
      Path notFollowed = current.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!followed.equals(notFollowed) || !followed.startsWith(realRoot)) {
        throw new IllegalArgumentException(
            "A required public evaluation path escapes through a link or reparse point.");
      }
    }
    return current;
  }

  private static Path requireOrCreateDirectory(Path directory, Path realBackend)
      throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectory(directory);
    }
    BasicFileAttributes attributes =
        Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isDirectory()
        || attributes.isSymbolicLink()
        || attributes.isOther()
        || Files.isSymbolicLink(directory)) {
      throw new IllegalArgumentException(
          "The packet output path contains a link or reparse point.");
    }
    Path followed = directory.toRealPath();
    Path notFollowed = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (!followed.equals(notFollowed) || !followed.startsWith(realBackend)) {
      throw new IllegalArgumentException("The packet output path escapes the backend directory.");
    }
    return directory;
  }

  static void requireSafeOutputDestination(Path output) throws IOException {
    if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    BasicFileAttributes attributes =
        Files.readAttributes(output, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()
        || attributes.isSymbolicLink()
        || attributes.isOther()
        || Files.isSymbolicLink(output)) {
      throw new IllegalArgumentException("The packet output file is not a safe regular file.");
    }
  }

  static void requireSafeTemporaryFile(Path temporary) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()
        || attributes.isSymbolicLink()
        || attributes.isOther()
        || Files.isSymbolicLink(temporary)) {
      throw new IllegalArgumentException("The packet temporary file is not a safe regular file.");
    }
  }

  private static void requireBackendRoot(Path backendRoot) throws IOException {
    Path pom = requireRegularFileWithoutLinks(backendRoot, Path.of("pom.xml"));
    if (!"backend".equals(backendRoot.getFileName().toString()) || Files.size(pom) == 0) {
      throw new IllegalArgumentException("Run the packet generator from the backend directory.");
    }
  }

  static void requireMirror(JsonNode repository, JsonNode source, JsonNode bundled) {
    if (!repository.equals(source) || !repository.equals(bundled)) {
      throw new IllegalArgumentException(
          "A bundled public evaluation resource differs from the checked-out source.");
    }
  }

  static void assertPinnedCleanCommit(String candidateCommit, String head, byte[] status) {
    if (candidateCommit == null
        || !COMMIT.matcher(candidateCommit).matches()
        || head == null
        || !COMMIT.matcher(head).matches()
        || !head.equals(candidateCommit)) {
      throw new IllegalArgumentException(
          "The candidate commit does not match the exact current HEAD.");
    }
    if (status == null || status.length != 0) {
      throw new IllegalArgumentException("The candidate worktree must be clean.");
    }
  }

  private static void verifyPinnedCleanCommit(Path repository, String candidateCommit) {
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

  private static byte[] runGit(Path repository, String... arguments) {
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
          readerExecutor.submit(() -> readBoundedProcessOutput(startedProcess.getInputStream()));
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        throw new IllegalArgumentException("Git state could not be verified.");
      }
      byte[] output = outputFuture.get(1, TimeUnit.SECONDS);
      if (process.exitValue() != 0) {
        throw new IllegalArgumentException("Git state could not be verified.");
      }
      return output;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("Git state verification was interrupted.");
    } catch (ExecutionException | TimeoutException | IOException | RuntimeException exception) {
      throw new IllegalArgumentException("Git state could not be verified.");
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

  private static byte[] readBoundedProcessOutput(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (output.size() + read > MAX_GIT_OUTPUT_BYTES) {
        throw new IOException("Git process output exceeded its bound.");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  static void writePacket(Path backendRoot, byte[] packet, Runnable verifyBeforePublish)
      throws IOException {
    Objects.requireNonNull(packet, "packet");
    Objects.requireNonNull(verifyBeforePublish, "verifyBeforePublish");
    Path outputDirectory = requireSafeOutputDirectory(backendRoot);
    Path output = outputDirectory.resolve(OUTPUT_FILE);
    boolean outputWasSafe = false;
    Path temporary = null;
    try {
      requireSafeOutputDestination(output);
      outputWasSafe = true;
      temporary = Files.createTempFile(outputDirectory, "public-v2-review-packet-", ".tmp");
      requireSafeTemporaryFile(temporary);
      try (OutputStream outputStream =
          Files.newOutputStream(
              temporary,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING,
              LinkOption.NOFOLLOW_LINKS)) {
        outputStream.write(packet);
      }
      verifyBeforePublish.run();
      Files.move(
          temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      temporary = null;
      Path realBackend = backendRoot.toAbsolutePath().normalize().toRealPath();
      if (!output.toRealPath().startsWith(realBackend)) {
        throw new IllegalArgumentException("The packet output escaped the backend directory.");
      }
    } catch (IOException | RuntimeException exception) {
      if (temporary != null) {
        Files.deleteIfExists(temporary);
      }
      if (outputWasSafe) {
        Files.deleteIfExists(output);
      }
      throw exception;
    }
  }
}
