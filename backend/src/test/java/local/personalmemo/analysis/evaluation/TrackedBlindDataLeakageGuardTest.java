package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class TrackedBlindDataLeakageGuardTest {
  private static final int PROCESS_OUTPUT_LIMIT = 2 * 1024 * 1024;
  private static final int TRACKED_EVALUATION_TEXT_LIMIT = 16 * 1024 * 1024;
  private static final Set<String> EVALUATION_TEXT_EXTENSIONS =
      Set.of(".json", ".jsonl", ".yaml", ".yml", ".csv", ".tsv");
  private static final List<String> BACKUP_OR_TEMP_SUFFIXES =
      List.of(".bak", ".backup", ".tmp", ".temp", ".old", ".orig", ".copy", ".swp", "~");
  private static final Pattern SENSITIVE_EVALUATION_PATH =
      Pattern.compile(
          "(?i)(?:^|[/\\\\._-])"
              + "(?:blind|annotations?|adjudications?|reviewers?|freezes?)"
              + "(?:[/\\\\._-]|$)");
  private static final Pattern BLIND_SPLIT = Pattern.compile("\"split\"\\s*:\\s*\"BLIND\"");
  private static final Pattern INDEPENDENT_SOURCE =
      Pattern.compile("\"sourcePolicy\"\\s*:\\s*\"INDEPENDENT_HUMAN_CURATED\"");
  private static final Pattern YAML_BLIND_SPLIT =
      Pattern.compile("(?im)^\\s*['\"]?split['\"]?\\s*:\\s*['\"]?BLIND['\"]?\\s*(?:#.*)?$");
  private static final Pattern YAML_INDEPENDENT_SOURCE =
      Pattern.compile(
          "(?im)^\\s*['\"]?sourcePolicy['\"]?\\s*:\\s*['\"]?"
              + "INDEPENDENT_HUMAN_CURATED['\"]?\\s*(?:#.*)?$");
  private static final Pattern DELIMITED_BLIND_VALUE =
      Pattern.compile("(?im)(?:^|[,\\t])\\s*['\"]?BLIND['\"]?\\s*(?=[,\\t]|$)");
  private static final Pattern DELIMITED_INDEPENDENT_SOURCE =
      Pattern.compile(
          "(?im)(?:^|[,\\t])\\s*['\"]?INDEPENDENT_HUMAN_CURATED['\"]?" + "\\s*(?=[,\\t]|$)");
  private static final String REVIEW_ARTIFACT_FIELD =
      "(?:review(?:SchemaVersion|Kind)|reviewer(?:Id|Ids|Token|Tokens)|"
          + "adjudicator(?:Id|Token)|annotationSchemaVersion|"
          + "adjudication(?:Status|ProtocolVersion))";
  private static final Pattern JSON_REVIEW_ARTIFACT_VALUE =
      Pattern.compile("(?i)\"" + REVIEW_ARTIFACT_FIELD + "\"\\s*:\\s*\"[^\"\\r\\n]+\"");
  private static final Pattern JSON_REVIEW_ARTIFACT_ARRAY =
      Pattern.compile("(?i)\"(?:reviewerIds|reviewerTokens)\"\\s*:\\s*\\[");
  private static final Pattern YAML_REVIEW_ARTIFACT_VALUE =
      Pattern.compile("(?im)^\\s*['\"]?" + REVIEW_ARTIFACT_FIELD + "['\"]?\\s*:\\s*(?!\\{)\\S+.*$");
  private static final Pattern DELIMITED_REVIEW_ARTIFACT_HEADER =
      Pattern.compile(
          "(?im)(?:^|[,\\t])\\s*['\"]?" + REVIEW_ARTIFACT_FIELD + "['\"]?\\s*(?=[,\\t]|$)");

  @Test
  void trackedEvaluationTextDoesNotContainBlindOrReviewerArtifacts() {
    Path repository = findRepositoryRoot();
    if (repository == null && isPublicCi()) {
      throw new AssertionError(
          "Git metadata is required for the tracked evaluation leakage guard.");
    }
    Assumptions.assumeTrue(
        repository != null,
        "Git metadata is unavailable; the tracked-file leakage guard runs in public CI checkout.");

    byte[] output = runGit(repository, "ls-files", "-z");
    boolean leaked =
        Arrays.stream(new String(output, StandardCharsets.UTF_8).split("\\u0000", -1))
            .filter(name -> !name.isBlank())
            .anyMatch(name -> trackedPathLeaks(repository, name));

    assertThat(leaked)
        .as("tracked evaluation text must not contain private blind or reviewer artifacts")
        .isFalse();
  }

  @Test
  void scansRelevantTextFormatsAndTheirBackupOrTemporaryVariants() {
    for (String path :
        List.of(
            "release.json",
            "release.JSONL",
            "release.yaml",
            "release.yml",
            "release.csv",
            "release.tsv",
            "release.json.bak",
            "release.yaml.tmp",
            "release.tsv~",
            "release.json.backup~")) {
      assertThat(isRelevantEvaluationTextPath(path)).as(path).isTrue();
    }
    assertThat(isRelevantEvaluationTextPath("release.md")).isFalse();
    assertThat(isRelevantEvaluationTextPath("release.json.exe.tmp")).isFalse();
  }

  @Test
  void rejectsSensitiveArtifactNamesRegardlessOfExtensionWithoutFlaggingPublicSchemas() {
    for (String path :
        List.of(
            "reviewer-input.md",
            "private/annotation-input.bin",
            "private/adjudication.tsv.enc",
            "private/evaluation-reviewers/manifest.dat",
            "private/freeze-input.zip",
            "private/blind-evaluation/cases.age")) {
      assertThat(isSensitiveEvaluationArtifactPath(path)).isTrue();
    }
    assertThat(
            isSensitiveEvaluationArtifactPath(
                "contracts/korean-memo-evaluation-review.schema.json"))
        .isFalse();
    assertThat(isSensitiveEvaluationArtifactPath("notes/blindspot.json")).isFalse();
    assertThat(isSensitiveEvaluationArtifactPath("TrackedBlindDataLeakageGuardTest.java"))
        .isFalse();
    assertThat(containsDisallowedControlCharacter("safe\ntext\r\nwith\ttabs")).isFalse();
    assertThat(containsDisallowedControlCharacter("binary\u0000payload")).isTrue();
  }

  @Test
  void detectsBlindReviewerAndAdjudicationValuesWithoutFlaggingSchemaDefinitions() {
    assertThat(containsSensitiveEvaluationMarker("{\"split\":\"BLIND\"}")).isTrue();
    assertThat(
            containsSensitiveEvaluationMarker("{\"sourcePolicy\":\"INDEPENDENT_HUMAN_CURATED\"}"))
        .isTrue();
    assertThat(containsSensitiveEvaluationMarker("{\"reviewKind\":\"PUBLIC_V2_DATE_ITEM_GOLD\"}"))
        .isTrue();
    assertThat(containsSensitiveEvaluationMarker("{\"reviewerId\":\"reviewer-a\"}")).isTrue();
    assertThat(containsSensitiveEvaluationMarker("adjudicationStatus: COMPLETE")).isTrue();
    assertThat(containsSensitiveEvaluationMarker("caseId,reviewerToken,label")).isTrue();
    assertThat(containsSensitiveEvaluationMarker("caseId\tadjudicationProtocolVersion\tlabel"))
        .isTrue();
    assertThat(
            containsSensitiveEvaluationMarker(
                """
                {
                  "properties": {
                    "split": {"enum": ["BLIND"]},
                    "sourcePolicy": {"const": "INDEPENDENT_HUMAN_CURATED"},
                    "reviewKind": {"const": "PUBLIC_V2_DATE_ITEM_GOLD"},
                    "reviewerId": {"type": "string"},
                    "adjudicationStatus": {"enum": ["COMPLETE"]}
                  }
                }
                """))
        .isFalse();
  }

  static boolean isRelevantEvaluationTextPath(String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    String candidate = name.toLowerCase(Locale.ROOT);
    boolean suffixRemoved;
    do {
      suffixRemoved = false;
      for (String suffix : BACKUP_OR_TEMP_SUFFIXES) {
        if (candidate.endsWith(suffix)) {
          candidate = candidate.substring(0, candidate.length() - suffix.length());
          suffixRemoved = true;
          break;
        }
      }
    } while (suffixRemoved);
    String normalized = candidate;
    return EVALUATION_TEXT_EXTENSIONS.stream().anyMatch(normalized::endsWith);
  }

  static boolean isSensitiveEvaluationArtifactPath(String name) {
    return name != null && SENSITIVE_EVALUATION_PATH.matcher(name).find();
  }

  private boolean trackedPathLeaks(Path repository, String name) {
    if (isSensitiveEvaluationArtifactPath(name)) {
      return true;
    }
    if (!isRelevantEvaluationTextPath(name)) {
      return false;
    }
    Path path = repository.resolve(name).normalize();
    if (!path.startsWith(repository) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new AssertionError("Tracked evaluation text could not be inspected safely.");
    }
    return containsSensitiveEvaluationMarker(path);
  }

  private boolean containsSensitiveEvaluationMarker(Path path) {
    try {
      if (Files.size(path) > TRACKED_EVALUATION_TEXT_LIMIT) {
        throw new AssertionError(
            "Tracked evaluation text exceeds the leakage-inspection boundary.");
      }
      String value = Files.readString(path, StandardCharsets.UTF_8);
      if (containsDisallowedControlCharacter(value)) {
        throw new AssertionError("Tracked evaluation text could not be inspected safely.");
      }
      return containsSensitiveEvaluationMarker(value);
    } catch (Exception exception) {
      throw new AssertionError("Tracked evaluation text could not be inspected safely.");
    }
  }

  static boolean containsSensitiveEvaluationMarker(String value) {
    return BLIND_SPLIT.matcher(value).find()
        || INDEPENDENT_SOURCE.matcher(value).find()
        || YAML_BLIND_SPLIT.matcher(value).find()
        || YAML_INDEPENDENT_SOURCE.matcher(value).find()
        || DELIMITED_BLIND_VALUE.matcher(value).find()
        || DELIMITED_INDEPENDENT_SOURCE.matcher(value).find()
        || JSON_REVIEW_ARTIFACT_VALUE.matcher(value).find()
        || JSON_REVIEW_ARTIFACT_ARRAY.matcher(value).find()
        || YAML_REVIEW_ARTIFACT_VALUE.matcher(value).find()
        || DELIMITED_REVIEW_ARTIFACT_HEADER.matcher(value).find();
  }

  static boolean containsDisallowedControlCharacter(String value) {
    return value
        .chars()
        .anyMatch(
            character ->
                character < 0x20 && character != '\n' && character != '\r' && character != '\t');
  }

  private boolean isPublicCi() {
    return "true".equalsIgnoreCase(System.getenv("CI"))
        || "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
  }

  private Path findRepositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve(".git"))
          && Files.isRegularFile(current.resolve("backend").resolve("pom.xml"))) {
        return current;
      }
      current = current.getParent();
    }
    return null;
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
      outputFuture = readerExecutor.submit(() -> readBounded(startedProcess.getInputStream()));
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Tracked files could not be enumerated for the leakage guard.");
      }
      byte[] output = outputFuture.get(1, TimeUnit.SECONDS);
      if (process.exitValue() != 0) {
        throw new AssertionError("Tracked files could not be enumerated for the leakage guard.");
      }
      return output;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Tracked-file leakage guard was interrupted.");
    } catch (ExecutionException
        | TimeoutException
        | java.io.IOException
        | RuntimeException exception) {
      throw new AssertionError("Tracked files could not be enumerated for the leakage guard.");
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

  private byte[] readBounded(java.io.InputStream input) throws java.io.IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (output.size() + read > PROCESS_OUTPUT_LIMIT) {
        throw new java.io.IOException("Process output limit exceeded.");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }
}
