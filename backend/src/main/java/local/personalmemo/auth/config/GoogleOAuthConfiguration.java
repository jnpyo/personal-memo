package local.personalmemo.auth.config;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration
@ConditionalOnProperty(prefix = "app.auth.google", name = "enabled", havingValue = "true")
public class GoogleOAuthConfiguration {
  @Bean
  ClientRegistrationRepository clientRegistrationRepository(
      AuthProperties properties, Environment environment) {
    AuthProperties.Google google = properties.google();
    if (google.clientId() == null
        || google.clientId().isBlank()
        || google.clientSecret() == null
        || google.clientSecret().isBlank()) {
      throw new IllegalStateException(
          "GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are required when Google auth is enabled.");
    }
    String redirectUri = normalizeRedirectUri(google.redirectUri());
    if (environment.matchesProfiles("prod")) {
      requireProductionRedirectUri(redirectUri);
    }
    ClientRegistration registration =
        CommonOAuth2Provider.GOOGLE
            .getBuilder("google")
            .clientId(google.clientId())
            .clientSecret(google.clientSecret())
            .redirectUri(redirectUri)
            .scope(List.of("openid", "profile", "email"))
            .build();
    return new InMemoryClientRegistrationRepository(registration);
  }

  @Bean
  OAuth2AuthorizedClientService authorizedClientService(
      ClientRegistrationRepository registrations) {
    return new InMemoryOAuth2AuthorizedClientService(registrations);
  }

  private void requireProductionRedirectUri(String redirectUri) {
    URI parsed;
    try {
      parsed = redirectUri == null ? null : URI.create(redirectUri);
    } catch (IllegalArgumentException ignored) {
      throw invalidProductionRedirectUri();
    }
    if (parsed == null
        || !parsed.isAbsolute()
        || !"https".equalsIgnoreCase(parsed.getScheme())
        || parsed.getHost() == null
        || parsed.getRawUserInfo() != null
        || parsed.getRawFragment() != null
        || !isPublicHost(parsed.getHost())) {
      throw invalidProductionRedirectUri();
    }
  }

  private String normalizeRedirectUri(String configuredRedirectUri) {
    String trimmed = configuredRedirectUri == null ? "" : configuredRedirectUri.trim();
    if (trimmed.isEmpty() || trimmed.contains("{")) {
      return trimmed;
    }
    try {
      return URI.create(trimmed).normalize().toASCIIString();
    } catch (IllegalArgumentException ignored) {
      return trimmed;
    }
  }

  private boolean isPublicHost(String configuredHost) {
    String host = configuredHost.toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (host.isBlank()
        || host.equals("localhost")
        || host.endsWith(".localhost")
        || host.endsWith(".local")
        || host.endsWith(".internal")
        || host.endsWith(".home")
        || host.endsWith(".lan")) {
      return false;
    }
    if (host.contains(":")) {
      return isPublicIpv6(host);
    }
    int[] ipv4 = parseIpv4(host);
    if (ipv4 != null) {
      return isPublicIpv4(ipv4);
    }
    if (host.chars().allMatch(character -> Character.isDigit(character) || character == '.')) {
      return false;
    }
    return isPublicDnsName(host);
  }

  private boolean isPublicDnsName(String host) {
    final String ascii;
    try {
      ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
    if (!ascii.contains(".")) {
      return false;
    }
    for (String label : ascii.split("\\.", -1)) {
      if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
        return false;
      }
    }
    return true;
  }

  private int[] parseIpv4(String host) {
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4) {
      return null;
    }
    int[] address = new int[4];
    for (int index = 0; index < parts.length; index++) {
      if (parts[index].isEmpty()
          || !parts[index].chars().allMatch(Character::isDigit)
          || parts[index].length() > 3
          || (parts[index].length() > 1 && parts[index].startsWith("0"))) {
        return null;
      }
      address[index] = Integer.parseInt(parts[index]);
      if (address[index] > 255) {
        return null;
      }
    }
    return address;
  }

  private boolean isPublicIpv4(int[] address) {
    int first = address[0];
    int second = address[1];
    int third = address[2];
    return first != 0
        && first != 10
        && first != 127
        && first < 224
        && !(first == 100 && second >= 64 && second <= 127)
        && !(first == 169 && second == 254)
        && !(first == 172 && second >= 16 && second <= 31)
        && !(first == 192 && second == 0 && third == 0)
        && !(first == 192 && second == 0 && third == 2)
        && !(first == 192 && second == 168)
        && !(first == 198 && (second == 18 || second == 19))
        && !(first == 198 && second == 51 && third == 100)
        && !(first == 203 && second == 0 && third == 113);
  }

  private boolean isPublicIpv6(String host) {
    if (host.contains("%")) {
      return false;
    }
    try {
      InetAddress address = InetAddress.getByName(host);
      byte[] bytes = address.getAddress();
      if (bytes.length == 4) {
        return isPublicIpv4(
            new int[] {bytes[0] & 0xff, bytes[1] & 0xff, bytes[2] & 0xff, bytes[3] & 0xff});
      }
      boolean globalUnicast = (bytes[0] & 0xe0) == 0x20;
      boolean documentationRange =
          (bytes[0] & 0xff) == 0x20
              && (bytes[1] & 0xff) == 0x01
              && (bytes[2] & 0xff) == 0x0d
              && (bytes[3] & 0xff) == 0xb8;
      return globalUnicast
          && !documentationRange
          && !address.isAnyLocalAddress()
          && !address.isLoopbackAddress()
          && !address.isLinkLocalAddress()
          && !address.isSiteLocalAddress()
          && !address.isMulticastAddress();
    } catch (UnknownHostException ignored) {
      return false;
    }
  }

  private IllegalStateException invalidProductionRedirectUri() {
    return new IllegalStateException(
        "GOOGLE_REDIRECT_URI must be a public absolute HTTPS URI without userinfo or a fragment when Google auth is enabled in production.");
  }
}
