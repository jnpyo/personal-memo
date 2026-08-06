package local.personalmemo.auth.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class SecurityErrorWriter {
  private final ObjectMapper json;

  SecurityErrorWriter(ObjectMapper json) {
    this.json = json;
  }

  void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response
        .getOutputStream()
        .write(
            json.writeValueAsBytes(new ErrorResponse(code, message, List.of(), UUID.randomUUID())));
  }

  private record ErrorResponse(
      String code, String message, List<Object> fieldErrors, UUID correlationId) {}
}
