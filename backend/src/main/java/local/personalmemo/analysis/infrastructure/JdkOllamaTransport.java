package local.personalmemo.analysis.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class JdkOllamaTransport implements OllamaTransport {
  private static final ProxySelector DIRECT_ONLY_PROXY_SELECTOR =
      new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
          return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
          // The direct-only client has no alternate proxy route to notify.
        }
      };

  private final HttpClient client;
  private final Map<URI, Set<String>> allowedRequests;
  private final int maxRequestBytes;
  private final int maxResponseBytes;

  JdkOllamaTransport(OllamaLocalModelProperties properties) {
    Objects.requireNonNull(properties, "properties").requireEnabledConfiguration();
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(DIRECT_ONLY_PROXY_SELECTOR)
            .build();
    this.allowedRequests =
        Map.of(
            properties.endpoint("/api/tags"), Set.of("GET"),
            properties.endpoint("/api/chat"), Set.of("POST"));
    this.maxRequestBytes = properties.getMaxRequestBytes();
    this.maxResponseBytes = properties.getMaxResponseBytes();
  }

  @Override
  public OllamaTransportResponse exchange(OllamaTransportRequest request)
      throws IOException, InterruptedException {
    OllamaTransportRequest bounded = Objects.requireNonNull(request, "request");
    Set<String> allowedMethods = allowedRequests.get(bounded.uri());
    if (allowedMethods == null || !allowedMethods.contains(bounded.method())) {
      throw new OllamaProtocolException();
    }
    byte[] body = bounded.body();
    if (body.length > maxRequestBytes
        || ("GET".equals(bounded.method()) && body.length != 0)
        || ("POST".equals(bounded.method()) && body.length == 0)) {
      throw new OllamaProtocolException();
    }

    HttpRequest.Builder builder =
        HttpRequest.newBuilder(bounded.uri())
            .timeout(bounded.timeout())
            .header("Accept", "application/json");
    if ("GET".equals(bounded.method())) {
      builder.GET();
    } else {
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    }

    HttpResponse<InputStream> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    if (declaredLength > maxResponseBytes) {
      try (InputStream ignored = response.body()) {
        throw new OllamaProtocolException();
      }
    }
    byte[] responseBody;
    try (InputStream input = response.body()) {
      responseBody = input.readNBytes(maxResponseBytes + 1);
    }
    if (responseBody.length > maxResponseBytes) {
      throw new OllamaProtocolException();
    }
    return new OllamaTransportResponse(
        response.statusCode(),
        response.headers().firstValue("Content-Type").orElse(""),
        responseBody);
  }
}
