package local.personalmemo.analysis.application;

import java.util.Objects;

/** Sanitized infrastructure failure from one bounded cloud gateway attempt. */
public final class CloudGatewayInvocationException extends RuntimeException {
  public enum Reason {
    CALLER_INTERRUPTED,
    UNEXPECTED_FAILURE
  }

  private final Reason reason;

  private CloudGatewayInvocationException(Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public static CloudGatewayInvocationException callerInterrupted() {
    return new CloudGatewayInvocationException(
        Reason.CALLER_INTERRUPTED, "Cloud gateway invocation was interrupted.");
  }

  public static CloudGatewayInvocationException unexpectedFailure() {
    return new CloudGatewayInvocationException(
        Reason.UNEXPECTED_FAILURE, "Cloud gateway invocation failed unexpectedly.");
  }

  public Reason reason() {
    return reason;
  }
}
