package local.personalmemo.taxonomy.domain;

import java.text.Normalizer;
import java.util.Locale;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;

@Component
public class TagNormalizer {
  private static final int MAX_TAG_LENGTH = 100;
  private static final int MAX_TAG_UTF16_LENGTH = MAX_TAG_LENGTH * 2;

  public NormalizedTag normalize(String rawName) {
    if (rawName == null
        || rawName.length() > MAX_TAG_UTF16_LENGTH
        || rawName.indexOf('\0') >= 0
        || hasUnpairedSurrogate(rawName)
        || rawName.codePointCount(0, rawName.length()) > MAX_TAG_LENGTH) {
      throw invalidTag();
    }

    String canonicalName =
        Normalizer.normalize(rawName, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
    if (!hasValidLength(canonicalName)) {
      throw invalidTag();
    }

    String normalizedName = canonicalName.toLowerCase(Locale.ROOT);
    if (!hasValidLength(normalizedName)) {
      throw invalidTag();
    }
    return new NormalizedTag(canonicalName, normalizedName);
  }

  private boolean hasValidLength(String value) {
    int length = value.codePointCount(0, value.length());
    return length >= 1 && length <= MAX_TAG_LENGTH;
  }

  private boolean hasUnpairedSurrogate(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return true;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }

  private DomainException invalidTag() {
    return DomainException.invalid(
        "INVALID_TAG_NAME",
        "A tag name must be well-formed, exclude U+0000, and contain between 1 and 100 "
            + "Unicode code points before and after normalization.");
  }

  public record NormalizedTag(String canonicalName, String normalizedName) {}
}
