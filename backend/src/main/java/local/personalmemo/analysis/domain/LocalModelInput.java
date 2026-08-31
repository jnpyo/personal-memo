package local.personalmemo.analysis.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded raw revision input that may be transferred only to a machine-local model gateway. */
public record LocalModelInput(
    String memoContent,
    Instant referenceInstant,
    String timeZone,
    List<ApprovedCorrectionContext.Hint> approvedCorrectionHints) {
  public static final int MAX_MEMO_UTF16_UNITS = 20_000;
  public static final int MAX_MEMO_UTF8_BYTES = 80_000;
  public static final int MAX_TIME_ZONE_LENGTH = 64;

  public LocalModelInput(String memoContent, Instant referenceInstant, String timeZone) {
    this(memoContent, referenceInstant, timeZone, List.of());
  }

  public LocalModelInput {
    memoContent = Objects.requireNonNull(memoContent, "memoContent");
    referenceInstant = Objects.requireNonNull(referenceInstant, "referenceInstant");
    timeZone = Objects.requireNonNull(timeZone, "timeZone");
    if (memoContent.isBlank()
        || memoContent.length() > MAX_MEMO_UTF16_UNITS
        || memoContent.getBytes(StandardCharsets.UTF_8).length > MAX_MEMO_UTF8_BYTES
        || !hasWellFormedUtf16(memoContent)) {
      throw new IllegalArgumentException("memoContent is outside the local-model input bounds.");
    }
    if (timeZone.isBlank()
        || timeZone.codePointCount(0, timeZone.length()) > MAX_TIME_ZONE_LENGTH
        || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
      throw new IllegalArgumentException("timeZone must be a bounded IANA time-zone identifier.");
    }
    approvedCorrectionHints =
        List.copyOf(Objects.requireNonNull(approvedCorrectionHints, "approvedCorrectionHints"));
    if (approvedCorrectionHints.size() > ApprovedCorrectionContext.MAX_SIGNALS) {
      throw new IllegalArgumentException("Too many approved correction hints were provided.");
    }
    Set<String> uniqueAnchors = new HashSet<>();
    Set<String> safeAnchors = new HashSet<>();
    ApprovedCorrectionAnchorPolicy.targetAnchors(memoContent)
        .forEach(anchor -> safeAnchors.add(anchor.text()));
    for (ApprovedCorrectionContext.Hint hint : approvedCorrectionHints) {
      Objects.requireNonNull(hint, "approvedCorrectionHint");
      if (!safeAnchors.contains(hint.anchorText()) || !uniqueAnchors.add(hint.anchorText())) {
        throw new IllegalArgumentException(
            "Approved correction hints must be safe unique memo substrings.");
      }
    }
  }

  private static boolean hasWellFormedUtf16(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return false;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String toString() {
    return "LocalModelInput[memoContent=redacted, referenceInstant="
        + referenceInstant
        + ", timeZone="
        + timeZone
        + ", approvedCorrectionHintCount="
        + approvedCorrectionHints.size()
        + "/redacted"
        + "]";
  }
}
