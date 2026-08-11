package local.personalmemo.analysis.domain;

/** Local reason that one bounded cloud gateway observation ended. */
public enum CloudGatewayAttemptTermination {
  GATEWAY_RESULT,
  EXECUTOR_REJECTED,
  TIMEOUT,
  CALLER_INTERRUPTED,
  UNEXPECTED_EXCEPTION
}
