package local.personalmemo.taxonomy.domain;

import java.text.Normalizer;
import java.util.Locale;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;

@Component
public class TagNormalizer {
  private static final int MAX_TAG_LENGTH = 100;

  public NormalizedTag normalize(String rawName) {
    if (rawName == null) {
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

  private DomainException invalidTag() {
    return DomainException.invalid(
        "INVALID_TAG_NAME", "A tag name must contain between 1 and 100 characters.");
  }

  public record NormalizedTag(String canonicalName, String normalizedName) {}
}
