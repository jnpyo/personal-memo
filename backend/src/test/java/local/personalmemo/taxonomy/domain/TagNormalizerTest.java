package local.personalmemo.taxonomy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;

class TagNormalizerTest {

  private final TagNormalizer normalizer = new TagNormalizer();

  @Test
  void appliesNfkcWhitespaceAndCaseNormalizationDeterministically() {
    var normalized = normalizer.normalize("  ＯＳ\t  과제  ");

    assertThat(normalized.canonicalName()).isEqualTo("OS 과제");
    assertThat(normalized.normalizedName()).isEqualTo("os 과제");
  }

  @Test
  void rejectsNullBlankAndNamesLongerThanDatabaseLimit() {
    assertInvalid(null);
    assertInvalid(" \n\t ");
    assertInvalid("가".repeat(101));
  }

  @Test
  void rejectsNulAndUnpairedSurrogatesBeforeNormalization() {
    assertInvalid("\0");
    assertInvalid("valid\0tag");
    assertInvalid("\uD800");
    assertInvalid("\uDC00");
  }

  @Test
  void acceptsExactlyOneHundredNormalizedCharacters() {
    var normalized = normalizer.normalize("가".repeat(100));

    assertThat(normalized.canonicalName()).hasSize(100);
    assertThat(normalized.normalizedName()).hasSize(100);
  }

  @Test
  void acceptsExactlyOneHundredSupplementaryCodePoints() {
    String name = "😀".repeat(100);

    var normalized = normalizer.normalize(name);

    assertThat(normalized.canonicalName().codePointCount(0, normalized.canonicalName().length()))
        .isEqualTo(100);
    assertThat(normalized.normalizedName()).isEqualTo(name);
  }

  @Test
  void rejectsOneHundredAndOneSupplementaryCodePoints() {
    assertInvalid("😀".repeat(101));
  }

  @Test
  void rejectsNamesWhoseRootLowercaseFormExceedsTheDatabaseLimit() {
    assertInvalid("İ".repeat(100));
  }

  private void assertInvalid(String value) {
    assertThatThrownBy(() -> normalizer.normalize(value))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo("INVALID_TAG_NAME");
              assertThat(exception.status().value()).isEqualTo(422);
            });
  }
}
