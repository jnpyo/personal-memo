package local.personalmemo.analysis.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

interface OllamaTransport {
  OllamaTransportResponse exchange(OllamaTransportRequest request)
      throws IOException, InterruptedException;
}

record OllamaTransportRequest(String method, URI uri, byte[] body, Duration timeout) {
  OllamaTransportRequest {
    method = Objects.requireNonNull(method, "method");
    uri = Objects.requireNonNull(uri, "uri");
    body = Objects.requireNonNull(body, "body").clone();
    timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public byte[] body() {
    return body.clone();
  }
}

record OllamaTransportResponse(int statusCode, String contentType, byte[] body) {
  OllamaTransportResponse {
    contentType = Objects.requireNonNull(contentType, "contentType");
    body = Objects.requireNonNull(body, "body").clone();
  }

  @Override
  public byte[] body() {
    return body.clone();
  }
}
