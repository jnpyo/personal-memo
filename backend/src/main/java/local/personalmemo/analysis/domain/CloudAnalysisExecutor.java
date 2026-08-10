package local.personalmemo.analysis.domain;

/** Executes one provider-independent request for an already bound cloud gateway. */
@FunctionalInterface
public interface CloudAnalysisExecutor {
  CloudAnalysisResult execute(CloudAnalysisRequest request);
}
