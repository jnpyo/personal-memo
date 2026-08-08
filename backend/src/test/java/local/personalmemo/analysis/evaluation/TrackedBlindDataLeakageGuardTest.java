package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
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
  private static final int TRACKED_JSON_LIMIT = 16 * 1024 * 1024;
  private static final Pattern BLIND_SPLIT = Pattern.compile("\"split\"\\s*:\\s*\"BLIND\"");
  private static final Pattern INDEPENDENT_SOURCE =
      Pattern.compile("\"sourcePolicy\"\\s*:\\s*\"INDEPENDENT_HUMAN_CURATED\"");

  @Test
  void trackedJsonDoesNotContainBlindCases() {
    Path repository = findRepositoryRoot();
    Assumptions.assumeTrue(
        repository != null,
        "Git metadata is unavailable; the tracked-file leakage guard runs in public CI checkout.");

    byte[] output = runGit(repository, "ls-files", "-z");
    boolean leaked =
        Arrays.stream(new String(output, StandardCharsets.UTF_8).split("\\u0000", -1))
            .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
            .map(repository::resolve)
            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            .anyMatch(this::containsBlindMarker);

    assertThat(leaked).as("tracked JSON must not contain a separately held blind case").isFalse();
  }

  private boolean containsBlindMarker(Path path) {
    try {
      if (Files.size(path) > TRACKED_JSON_LIMIT) {
        throw new AssertionError("Tracked JSON exceeds the leakage-inspection boundary.");
      }
      String value = Files.readString(path, StandardCharsets.UTF_8);
      return BLIND_SPLIT.matcher(value).find() || INDEPENDENT_SOURCE.matcher(value).find();
    } catch (Exception exception) {
      throw new AssertionError("Tracked JSON could not be inspected for blind markers.");
    }
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
