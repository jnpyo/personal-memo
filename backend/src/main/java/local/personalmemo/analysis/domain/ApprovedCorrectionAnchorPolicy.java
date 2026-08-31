package local.personalmemo.analysis.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative lexical policy for private, machine-local approved-correction hints. */
public final class ApprovedCorrectionAnchorPolicy {
  public static final int MIN_CODE_POINTS = 3;
  public static final int MAX_CODE_POINTS = 32;
  public static final int MAX_UTF16_UNITS = MAX_CODE_POINTS * 2;

  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
  private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{4,}");
  private static final Set<String> GENERIC_TOKENS =
      Set.of("그리고", "하지만", "그러나", "메모", "예정", "오늘", "내일", "이번", "다음");
  private static final List<String> SENSITIVE_FRAGMENTS =
      List.of(
          "비밀번호",
          "패스워드",
          "인증번호",
          "주민번호",
          "계좌번호",
          "apikey",
          "api키",
          "password",
          "secret",
          "token",
          "oauth",
          "bearer");

  private ApprovedCorrectionAnchorPolicy() {}

  /** Returns safe, exact-unique target tokens with Java/JSON UTF-16 offsets. */
  public static List<Anchor> targetAnchors(String content) {
    if (!hasWellFormedUtf16(content)) {
      return List.of();
    }
    List<Anchor> candidates = new ArrayList<>();
    Map<String, Integer> normalizedCounts = new HashMap<>();
    Matcher matcher = TOKEN.matcher(content);
    while (matcher.find()) {
      String token = matcher.group();
      if (isSafeToken(token)) {
        String normalized = normalize(token);
        candidates.add(new Anchor(matcher.start(), matcher.end(), token, normalized));
        normalizedCounts.merge(normalized, 1, Integer::sum);
      }
    }
    List<Anchor> anchors = new ArrayList<>();
    for (Anchor candidate : candidates) {
      if (normalizedCounts.get(candidate.normalized()) == 1
          && isExactUnique(content, candidate.text(), candidate.startUtf16())) {
        anchors.add(candidate);
      }
    }
    return List.copyOf(anchors);
  }

  /** Returns only safe normalized lexical tokens; source content is never retained. */
  public static Set<String> normalizedSourceTokens(String content) {
    if (!hasWellFormedUtf16(content)) {
      return Set.of();
    }
    Map<String, Integer> normalizedCounts = new HashMap<>();
    Matcher matcher = TOKEN.matcher(content);
    while (matcher.find()) {
      String token = matcher.group();
      if (isSafeToken(token)) {
        normalizedCounts.merge(normalize(token), 1, Integer::sum);
      }
    }
    Set<String> tokens = new HashSet<>();
    normalizedCounts.forEach(
        (normalized, count) -> {
          if (count == 1) {
            tokens.add(normalized);
          }
        });
    return Set.copyOf(tokens);
  }

  /** Revalidates a persisted offset-only signal against the immutable target revision. */
  public static boolean isSafeUniqueAnchor(String content, int startUtf16, int endUtf16) {
    if (!hasWellFormedUtf16(content)
        || startUtf16 < 0
        || endUtf16 <= startUtf16
        || endUtf16 > content.length()
        || splitsSurrogatePair(content, startUtf16, endUtf16)) {
      return false;
    }
    return targetAnchors(content).stream()
        .anyMatch(anchor -> anchor.startUtf16() == startUtf16 && anchor.endUtf16() == endUtf16);
  }

  public static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  public static boolean isActionLike(Anchor anchor) {
    String normalized = anchor.normalized();
    return normalized.endsWith("하기") || normalized.endsWith("하다") || normalized.endsWith("해야");
  }

  private static boolean isSafeToken(String token) {
    int codePoints = token.codePointCount(0, token.length());
    if (codePoints < MIN_CODE_POINTS
        || codePoints > MAX_CODE_POINTS
        || token.length() > MAX_UTF16_UNITS
        || !TOKEN.matcher(token).matches()
        || hangulCount(token) < 2
        || LONG_DIGIT_RUN.matcher(token).find()) {
      return false;
    }
    String normalized = normalize(token);
    if (GENERIC_TOKENS.contains(normalized)) {
      return false;
    }
    for (String sensitive : SENSITIVE_FRAGMENTS) {
      if (normalized.contains(sensitive)) {
        return false;
      }
    }
    return true;
  }

  private static int hangulCount(String value) {
    int count = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      if ((codePoint >= 0xAC00 && codePoint <= 0xD7A3)
          || (codePoint >= 0x1100 && codePoint <= 0x11FF)
          || (codePoint >= 0x3130 && codePoint <= 0x318F)
          || (codePoint >= 0xA960 && codePoint <= 0xA97F)
          || (codePoint >= 0xD7B0 && codePoint <= 0xD7FF)) {
        count++;
      }
      offset += Character.charCount(codePoint);
    }
    return count;
  }

  private static boolean isExactUnique(String content, String token, int expectedStart) {
    int first = content.indexOf(token);
    return first == expectedStart && content.indexOf(token, first + token.length()) < 0;
  }

  private static boolean splitsSurrogatePair(String value, int start, int end) {
    return (start < value.length() && Character.isLowSurrogate(value.charAt(start)))
        || (end > 0 && Character.isHighSurrogate(value.charAt(end - 1)));
  }

  private static boolean hasWellFormedUtf16(String value) {
    if (value == null) {
      return false;
    }
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

  public record Anchor(int startUtf16, int endUtf16, String text, String normalized) {
    @Override
    public String toString() {
      return "Anchor[redacted]";
    }
  }
}
