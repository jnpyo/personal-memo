package local.personalmemo.analysis.domain;

import java.util.Objects;

/** Immutable descriptor/executor pair captured from one configured gateway state. */
public final class CloudGatewayBinding {
  private final CloudGatewayDescriptor descriptor;
  private final CloudGatewayBindingId bindingId;
  private final CloudAnalysisExecutor executor;

  public CloudGatewayBinding(CloudGatewayDescriptor descriptor, CloudAnalysisExecutor executor) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.bindingId = CloudGatewayBindingId.issue(descriptor);
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  public CloudGatewayDescriptor descriptor() {
    return descriptor;
  }

  public CloudGatewayBindingId bindingId() {
    return bindingId;
  }

  public CloudAnalysisResult execute(CloudAnalysisRequest request) {
    CloudAnalysisRequest requiredRequest = Objects.requireNonNull(request, "request");
    if (!descriptor.equals(requiredRequest.descriptor())) {
      throw new IllegalArgumentException(
          "The request descriptor does not match the bound cloud gateway.");
    }
    CloudAnalysisResult result = executor.execute(requiredRequest);
    if (result == null) {
      throw new IllegalStateException("The bound cloud gateway returned no result.");
    }
    return result;
  }

  @Override
  public String toString() {
    return "CloudGatewayBinding[descriptor="
        + descriptor
        + ", bindingId="
        + bindingId
        + ", executor=redacted]";
  }
}
