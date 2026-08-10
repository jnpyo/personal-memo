package local.personalmemo.analysis.domain;

import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/** Provider-independent result that deliberately carries no provider error text. */
public sealed interface CloudAnalysisResult
    permits CloudAnalysisResult.Success, CloudAnalysisResult.Failure {

  static Success success(ObjectNode proposal) {
    return new Success(proposal);
  }

  static Failure failure(CloudAnalysisFailureReason reason) {
    return new Failure(reason);
  }

  record Success(ObjectNode proposal) implements CloudAnalysisResult {
    public Success {
      proposal = Objects.requireNonNull(proposal, "proposal").deepCopy();
    }

    @Override
    public ObjectNode proposal() {
      return proposal.deepCopy();
    }
  }

  record Failure(CloudAnalysisFailureReason reason) implements CloudAnalysisResult {
    public Failure {
      reason = Objects.requireNonNull(reason, "reason");
    }
  }
}
