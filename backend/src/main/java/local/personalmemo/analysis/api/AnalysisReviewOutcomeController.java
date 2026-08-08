package local.personalmemo.analysis.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import local.personalmemo.analysis.application.AnalysisReviewOutcomeService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/analysis-review-outcomes")
public class AnalysisReviewOutcomeController {
  private final AnalysisReviewOutcomeService service;

  public AnalysisReviewOutcomeController(AnalysisReviewOutcomeService service) {
    this.service = service;
  }

  @GetMapping("/summary")
  ResponseEntity<AnalysisReviewOutcomeDtos.Summary> summary(
      @RequestParam(defaultValue = "14") @Min(1) @Max(90) int days) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary(days));
  }
}
