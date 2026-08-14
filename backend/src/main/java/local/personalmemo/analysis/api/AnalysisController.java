package local.personalmemo.analysis.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import local.personalmemo.analysis.application.AnalysisApplicationService;
import local.personalmemo.analysis.application.AnalysisRelationReviewService;
import local.personalmemo.analysis.application.AnalysisService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class AnalysisController {
  public static final String PROPOSAL_SCHEMA_VERSION_HEADER = "X-Analysis-Proposal-Schema-Version";

  private final AnalysisService analysisService;
  private final AnalysisApplicationService applicationService;
  private final AnalysisRelationReviewService relationReviewService;

  public AnalysisController(
      AnalysisService analysisService,
      AnalysisApplicationService applicationService,
      AnalysisRelationReviewService relationReviewService) {
    this.analysisService = analysisService;
    this.applicationService = applicationService;
    this.relationReviewService = relationReviewService;
  }

  @PostMapping("/memos/{id}/analysis-runs")
  AnalysisDtos.RunView start(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AnalysisDtos.Start body) {
    return analysisService.start(id, key, body);
  }

  @GetMapping("/analysis-proposals/{id}")
  ResponseEntity<JsonNode> proposal(
      @PathVariable UUID id,
      @RequestHeader(name = PROPOSAL_SCHEMA_VERSION_HEADER, required = false)
          String requestedSchemaVersion) {
    return proposalResponse(analysisService.proposal(id, requestedSchemaVersion));
  }

  @GetMapping("/analysis-proposals")
  ResponseEntity<List<AnalysisDtos.ProposalRecoveryView>> proposals(
      @RequestParam(name = "status") String status,
      @RequestParam(name = "limit", defaultValue = "1") int limit,
      @RequestHeader(name = PROPOSAL_SCHEMA_VERSION_HEADER, required = false)
          String requestedSchemaVersion) {
    return proposalResponse(
        analysisService.recoveryProposals(status, limit, requestedSchemaVersion));
  }

  private <T> ResponseEntity<T> proposalResponse(T body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .varyBy(PROPOSAL_SCHEMA_VERSION_HEADER)
        .body(body);
  }

  @GetMapping("/analysis-proposals/{id}/relation-review-candidates")
  ResponseEntity<List<AnalysisDtos.RelationReviewCandidate>> relationReviewCandidates(
      @PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(relationReviewService.candidates(id));
  }

  @PostMapping("/analysis-proposals/{id}/apply")
  AnalysisDtos.ApplicationView apply(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AnalysisDtos.Apply body) {
    return applicationService.apply(id, key, body);
  }

  @PostMapping("/analysis-proposals/{id}/reject")
  AnalysisDtos.ReviewDispositionView reject(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
    return analysisService.reject(id, key);
  }

  @PostMapping("/analysis-proposals/{id}/postpone")
  AnalysisDtos.ReviewDispositionView postpone(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
    return analysisService.postpone(id, key);
  }

  @PostMapping("/analysis-applications/{id}/undo")
  AnalysisDtos.ApplicationView undo(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
    return applicationService.undo(id, key);
  }

  @GetMapping("/analysis-applications/latest")
  AnalysisDtos.ApplicationRecoveryView latestApplication() {
    return applicationService.latest();
  }
}
