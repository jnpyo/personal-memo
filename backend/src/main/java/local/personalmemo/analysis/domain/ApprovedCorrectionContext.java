package local.personalmemo.analysis.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Offset-only, retry-stable context derived from same-owner user-approved corrections. */
public record ApprovedCorrectionContext(String version, List<Signal> signals) {
  public static final String CURRENT_VERSION = "approved-type-anchor-k3-v1";
  public static final int MAX_SIGNALS = 3;
  private static final Set<String> ITEM_KINDS =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD");

  public ApprovedCorrectionContext {
    if (!CURRENT_VERSION.equals(version)) {
      throw new IllegalArgumentException("Unsupported approved correction context version.");
    }
    signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
    if (signals.size() > MAX_SIGNALS) {
      throw new IllegalArgumentException(
          "Approved correction context cannot exceed three signals.");
    }
    Set<Span> spans = new HashSet<>();
    for (Signal signal : signals) {
      Objects.requireNonNull(signal, "signal");
      if (!spans.add(new Span(signal.startUtf16(), signal.endUtf16()))) {
        throw new IllegalArgumentException("Approved correction context spans must be unique.");
      }
    }
  }

  public int signalCount() {
    return signals.size();
  }

  /** Rehydrates prompt-safe hints only from the already-authorized target memo revision. */
  public List<Hint> rehydrate(String memoContent) {
    List<Hint> hints = new ArrayList<>();
    for (Signal signal : signals) {
      if (!ApprovedCorrectionAnchorPolicy.isSafeUniqueAnchor(
          memoContent, signal.startUtf16(), signal.endUtf16())) {
        throw new IllegalArgumentException(
            "Approved correction context does not match the target memo revision.");
      }
      hints.add(
          new Hint(
              memoContent.substring(signal.startUtf16(), signal.endUtf16()),
              signal.approvedKind()));
    }
    return List.copyOf(hints);
  }

  @Override
  public String toString() {
    return "ApprovedCorrectionContext[version="
        + version
        + ", signalCount="
        + signals.size()
        + ", signals=redacted]";
  }

  public record Signal(int startUtf16, int endUtf16, String approvedKind) {
    public Signal {
      if (startUtf16 < 0
          || endUtf16 <= startUtf16
          || endUtf16 - startUtf16 > ApprovedCorrectionAnchorPolicy.MAX_UTF16_UNITS) {
        throw new IllegalArgumentException("Approved correction signal offsets are invalid.");
      }
      if (approvedKind == null || !ITEM_KINDS.contains(approvedKind)) {
        throw new IllegalArgumentException("Approved correction signal kind is invalid.");
      }
    }

    @Override
    public String toString() {
      return "Signal[redacted]";
    }
  }

  public record Hint(String anchorText, String approvedKind) {
    public Hint {
      Objects.requireNonNull(anchorText, "anchorText");
      if (approvedKind == null || !ITEM_KINDS.contains(approvedKind)) {
        throw new IllegalArgumentException("Approved correction hint kind is invalid.");
      }
    }

    @Override
    public String toString() {
      return "Hint[redacted]";
    }
  }

  private record Span(int startUtf16, int endUtf16) {}
}
