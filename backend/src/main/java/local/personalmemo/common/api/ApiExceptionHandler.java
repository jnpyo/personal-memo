package local.personalmemo.common.api;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.UUID;
import local.personalmemo.common.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(DomainException.class)
  ResponseEntity<ErrorResponse> domain(DomainException exception) {
    return response(exception.status(), exception.code(), exception.getMessage(), List.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> beanValidation(MethodArgumentNotValidException exception) {
    List<ApiFieldError> fieldErrors =
        exception.getBindingResult().getFieldErrors().stream().map(this::toFieldError).toList();
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "VALIDATION_FAILED",
        "One or more request fields are invalid.",
        fieldErrors);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorResponse> constraintValidation(ConstraintViolationException exception) {
    List<ApiFieldError> fieldErrors =
        exception.getConstraintViolations().stream()
            .map(
                violation ->
                    new ApiFieldError(
                        violation.getPropertyPath().toString(), violation.getMessage()))
            .toList();
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "VALIDATION_FAILED",
        "One or more request fields are invalid.",
        fieldErrors);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> argumentTypeValidation(
      MethodArgumentTypeMismatchException exception) {
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "VALIDATION_FAILED",
        "One or more request fields are invalid.",
        List.of(new ApiFieldError(exception.getName(), "must be a valid value")));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson(HttpMessageNotReadableException exception) {
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "MALFORMED_JSON",
        "The request body is not valid JSON.",
        List.of());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ErrorResponse> invalid(IllegalArgumentException exception) {
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "INVALID_REQUEST",
        "The request contains an invalid value.",
        List.of());
  }

  private ApiFieldError toFieldError(FieldError error) {
    return new ApiFieldError(error.getField(), error.getDefaultMessage());
  }

  private ResponseEntity<ErrorResponse> response(
      HttpStatus status, String code, String message, List<ApiFieldError> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, UUID.randomUUID()));
  }

  record ErrorResponse(
      String code, String message, List<ApiFieldError> fieldErrors, UUID correlationId) {}

  record ApiFieldError(String field, String message) {}
}
