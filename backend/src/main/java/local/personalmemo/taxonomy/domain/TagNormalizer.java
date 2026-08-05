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
    if (canonicalName.isEmpty() || canonicalName.length() > MAX_TAG_LENGTH) {
      throw invalidTag();
    }

    return new NormalizedTag(canonicalName, canonicalName.toLowerCase(Locale.ROOT));
  }

  private DomainException invalidTag() {
    return DomainException.invalid(
        "INVALID_TAG_NAME", "A tag name must contain between 1 and 100 characters.");
  }

  public record NormalizedTag(String canonicalName, String normalizedName) {}
}
