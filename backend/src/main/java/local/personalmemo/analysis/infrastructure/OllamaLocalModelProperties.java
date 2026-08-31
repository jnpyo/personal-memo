package local.personalmemo.analysis.infrastructure;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.analysis.local-model")
public class OllamaLocalModelProperties {
  static final String DESCRIPTOR_PROVIDER_PREFIX = "ollama-local@";
  static final int MAX_DESCRIPTOR_FIELD_LENGTH = 64;
  static final int MAX_MODEL_TAG_LENGTH =
      MAX_DESCRIPTOR_FIELD_LENGTH - DESCRIPTOR_PROVIDER_PREFIX.length();
  static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  static final Duration MAX_REQUEST_TIMEOUT = Duration.ofMinutes(1);

  private boolean enabled;

  @NotNull private URI endpoint = URI.create("http://127.0.0.1:11434");

  @NotNull private List<URI> allowedDockerHostRelayOrigins = List.of();

  private String model = "";
  private String modelDigest = "";

  @NotNull private Duration connectTimeout = Duration.ofSeconds(3);
  @NotNull private Duration requestTimeout = Duration.ofSeconds(30);

  @Min(4 * 1024) @Max(256 * 1024) private int maxRequestBytes = 128 * 1024;

  @Min(1024) @Max(64 * 1024) private int maxResponseBytes = 64 * 1024;

  @Min(256) @Max(16 * 1024) private int maxModelOutputBytes = 8 * 1024;

  @Min(32) @Max(1024)
  // LFM2.5 may spend several hundred generation tokens on hidden reasoning even when think=false;
  // the accepted visible patch is still separately bounded by maxModelOutputBytes and JSON Schema.
  private int numPredict = 1024;

  @Min(512) @Max(8192) private int numContext = 4096;

  private int seed = 20_260_821;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public URI getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(URI endpoint) {
    this.endpoint = endpoint;
  }

  public List<URI> getAllowedDockerHostRelayOrigins() {
    return List.copyOf(allowedDockerHostRelayOrigins);
  }

  public void setAllowedDockerHostRelayOrigins(List<URI> allowedDockerHostRelayOrigins) {
    this.allowedDockerHostRelayOrigins =
        allowedDockerHostRelayOrigins == null ? null : List.copyOf(allowedDockerHostRelayOrigins);
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getModelDigest() {
    return modelDigest;
  }

  public void setModelDigest(String modelDigest) {
    this.modelDigest = modelDigest;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public int getMaxRequestBytes() {
    return maxRequestBytes;
  }

  public void setMaxRequestBytes(int maxRequestBytes) {
    this.maxRequestBytes = maxRequestBytes;
  }

  public int getMaxResponseBytes() {
    return maxResponseBytes;
  }

  public void setMaxResponseBytes(int maxResponseBytes) {
    this.maxResponseBytes = maxResponseBytes;
  }

  public int getMaxModelOutputBytes() {
    return maxModelOutputBytes;
  }

  public void setMaxModelOutputBytes(int maxModelOutputBytes) {
    this.maxModelOutputBytes = maxModelOutputBytes;
  }

  public int getNumPredict() {
    return numPredict;
  }

  public void setNumPredict(int numPredict) {
    this.numPredict = numPredict;
  }

  public int getNumContext() {
    return numContext;
  }

  public void setNumContext(int numContext) {
    this.numContext = numContext;
  }

  public int getSeed() {
    return seed;
  }

  public void setSeed(int seed) {
    this.seed = seed;
  }

  @AssertTrue(message = "local-model endpoint and relay allowlist must remain machine-local") public boolean isEndpointPolicyValid() {
    if (endpoint == null || allowedDockerHostRelayOrigins == null) {
      return false;
    }
    String endpointOrigin = normalizedOrigin(endpoint);
    if (endpointOrigin == null) {
      return false;
    }
    if ("127.0.0.1".equals(endpoint.getHost())) {
      return allowedRelayOriginsAreValid();
    }
    return isDockerHost(endpoint)
        && allowedRelayOriginsAreValid()
        && allowedDockerHostRelayOrigins.stream()
            .map(OllamaLocalModelProperties::normalizedOrigin)
            .anyMatch(endpointOrigin::equals);
  }

  @AssertTrue(message = "enabled local-model configuration requires an exact model and digest") public boolean isModelIdentityValid() {
    return !enabled || isExactModelIdentityValid(model, modelDigest);
  }

  static boolean isExactModelIdentityValid(String model, String modelDigest) {
    return model != null
        && !model.isBlank()
        && model.equals(model.strip())
        && model.codePointCount(0, model.length()) <= MAX_MODEL_TAG_LENGTH
        && model.matches("[A-Za-z0-9][A-Za-z0-9._/-]*(?::[A-Za-z0-9._-]+)?")
        && modelDigest != null
        && modelDigest.matches("[0-9a-f]{64}");
  }

  @AssertTrue(message = "local-model timeouts and response bounds are invalid") public boolean isExecutionPolicyValid() {
    return positiveAtMost(connectTimeout, MAX_CONNECT_TIMEOUT)
        && positiveAtMost(requestTimeout, MAX_REQUEST_TIMEOUT)
        && maxModelOutputBytes <= maxResponseBytes;
  }

  void requireEnabledConfiguration() {
    if (!enabled
        || !isEndpointPolicyValid()
        || !isModelIdentityValid()
        || !isExecutionPolicyValid()) {
      throw new IllegalArgumentException("The enabled local-model configuration is invalid.");
    }
  }

  URI endpoint(String path) {
    requireEnabledConfiguration();
    if (path == null || !path.startsWith("/api/") || path.contains("?") || path.contains("#")) {
      throw new IllegalArgumentException("The Ollama path is not allow-listed.");
    }
    return URI.create(normalizedOrigin(endpoint) + path);
  }

  private boolean allowedRelayOriginsAreValid() {
    Set<String> uniqueOrigins = new HashSet<>();
    for (URI candidate : allowedDockerHostRelayOrigins) {
      String origin = normalizedOrigin(candidate);
      if (origin == null || !isDockerHost(candidate) || !uniqueOrigins.add(origin)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isDockerHost(URI uri) {
    return uri != null && "host.docker.internal".equalsIgnoreCase(uri.getHost());
  }

  private static String normalizedOrigin(URI value) {
    if (value == null
        || !"http".equalsIgnoreCase(value.getScheme())
        || value.getHost() == null
        || value.getPort() < 1
        || value.getPort() > 65_535
        || value.getRawUserInfo() != null
        || value.getRawQuery() != null
        || value.getRawFragment() != null
        || !(value.getRawPath() == null
            || value.getRawPath().isEmpty()
            || "/".equals(value.getRawPath()))) {
      return null;
    }
    return "http://" + value.getHost().toLowerCase(Locale.ROOT) + ":" + value.getPort();
  }

  private static boolean positiveAtMost(Duration value, Duration maximum) {
    return value != null && !value.isNegative() && !value.isZero() && value.compareTo(maximum) <= 0;
  }
}
