package local.personalmemo.common.error;

import org.springframework.http.HttpStatus;

public final class DomainException extends RuntimeException {
  private final String code;
  private final HttpStatus status;

  private DomainException(String code, HttpStatus status, String message) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public static DomainException conflict(String code, String message) {
    return new DomainException(code, HttpStatus.CONFLICT, message);
  }

  public static DomainException unauthorized(String code, String message) {
    return new DomainException(code, HttpStatus.UNAUTHORIZED, message);
  }

  public static DomainException forbidden(String code, String message) {
    return new DomainException(code, HttpStatus.FORBIDDEN, message);
  }

  public static DomainException invalid(String code, String message) {
    return new DomainException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  public static DomainException notFound(String resourceName) {
    return new DomainException(
        "RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, resourceName + " was not found.");
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }
}
