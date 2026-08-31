package local.personalmemo.calendar.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.calendar-feed.publication")
public record CalendarFeedPublicationProperties(
    boolean enabled, String publicOrigin, String consentPolicyVersion) {
  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern DNS_TOP_LEVEL_LABEL =
      Pattern.compile("[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern IPV4_SHAPED = Pattern.compile("[0-9]+(?:\\.[0-9]+){1,3}");
  private static final Pattern CONSENT_POLICY_VERSION =
      Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

  public CalendarFeedPublicationProperties {
    publicOrigin = publicOrigin == null ? "" : publicOrigin;
    consentPolicyVersion = consentPolicyVersion == null ? "" : consentPolicyVersion;
    if (!enabled) {
      if (!publicOrigin.isBlank() || !consentPolicyVersion.isBlank()) {
        throw new IllegalArgumentException(
            "app.calendar-feed.publication public-origin and consent-policy-version must be blank when publication is disabled");
      }
    } else {
      requireCanonicalHttpsOrigin(publicOrigin);
      requireCanonicalConsentPolicyVersion(consentPolicyVersion);
    }
  }

  private static void requireCanonicalConsentPolicyVersion(String value) {
    if (!CONSENT_POLICY_VERSION.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "app.calendar-feed.publication.consent-policy-version must be a canonical lowercase policy identifier");
    }
  }

  private static void requireCanonicalHttpsOrigin(String value) {
    if (value.isBlank() || value.length() > 255 || !isAscii(value)) {
      throw invalidOrigin();
    }

    URI origin;
    try {
      origin = new URI(value);
    } catch (URISyntaxException exception) {
      throw invalidOrigin(exception);
    }

    String host = origin.getHost();
    if (!"https".equals(origin.getScheme())
        || origin.isOpaque()
        || origin.getRawAuthority() == null
        || origin.getRawUserInfo() != null
        || host == null
        || host.isBlank()
        || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
        || origin.getRawQuery() != null
        || origin.getRawFragment() != null) {
      throw invalidOrigin();
    }

    String canonicalHost = host.toLowerCase(Locale.ROOT);
    if (!isAsciiFqdn(canonicalHost)) {
      throw invalidOrigin();
    }

    int port = origin.getPort();
    if (port == 0 || port > 65535 || port == 443) {
      throw invalidOrigin();
    }
    String canonical = "https://" + canonicalHost + (port < 0 ? "" : ":" + port);
    if (!value.equals(canonical)) {
      throw invalidOrigin();
    }
  }

  private static boolean isAsciiFqdn(String host) {
    if (host.length() > 253
        || host.equals("localhost")
        || host.endsWith(".localhost")
        || host.indexOf('.') < 0
        || host.indexOf(':') >= 0
        || IPV4_SHAPED.matcher(host).matches()) {
      return false;
    }
    String[] labels = host.split("\\.", -1);
    if (labels.length < 2 || !DNS_TOP_LEVEL_LABEL.matcher(labels[labels.length - 1]).matches()) {
      return false;
    }
    for (String label : labels) {
      if (!DNS_LABEL.matcher(label).matches()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAscii(String value) {
    return value.chars().allMatch(character -> character <= 0x7f);
  }

  private static IllegalArgumentException invalidOrigin() {
    return new IllegalArgumentException(
        "app.calendar-feed.publication.public-origin must be a canonical HTTPS origin with an ASCII FQDN");
  }

  private static IllegalArgumentException invalidOrigin(Exception cause) {
    return new IllegalArgumentException(
        "app.calendar-feed.publication.public-origin must be a canonical HTTPS origin with an ASCII FQDN",
        cause);
  }
}
