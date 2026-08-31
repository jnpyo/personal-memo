package local.personalmemo.analysis.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.analysis.invocation")
public class AnalysisInvocationProperties {
  public static final int APPROVED_CORRECTION_CONTEXT_K = 3;

  @NotNull private AnalysisInvocationMode mode = AnalysisInvocationMode.UNCERTAINTY_ONLY;

  private boolean approvedCorrectionsEnabled;

  @Min(APPROVED_CORRECTION_CONTEXT_K) @Max(APPROVED_CORRECTION_CONTEXT_K) private int approvedCorrectionContextK = APPROVED_CORRECTION_CONTEXT_K;

  public AnalysisInvocationMode getMode() {
    return mode;
  }

  public void setMode(AnalysisInvocationMode mode) {
    this.mode = mode;
  }

  public boolean isApprovedCorrectionsEnabled() {
    return approvedCorrectionsEnabled;
  }

  public void setApprovedCorrectionsEnabled(boolean approvedCorrectionsEnabled) {
    this.approvedCorrectionsEnabled = approvedCorrectionsEnabled;
  }

  public int getApprovedCorrectionContextK() {
    return approvedCorrectionContextK;
  }

  public void setApprovedCorrectionContextK(int approvedCorrectionContextK) {
    this.approvedCorrectionContextK = approvedCorrectionContextK;
  }

  @AssertTrue(message = "approved corrections require AI_PREFERRED invocation mode") public boolean isApprovedCorrectionsModeValid() {
    return !approvedCorrectionsEnabled || mode == AnalysisInvocationMode.AI_PREFERRED;
  }
}
