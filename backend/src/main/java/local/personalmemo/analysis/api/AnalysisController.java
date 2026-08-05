package local.personalmemo.analysis.api;
import tools.jackson.databind.JsonNode; import jakarta.validation.Valid; import java.util.*; import local.personalmemo.analysis.application.AnalysisService; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") public class AnalysisController {private final AnalysisService s;public AnalysisController(AnalysisService s){this.s=s;}
 @PostMapping("/memos/{id}/analysis-runs") AnalysisDtos.RunView start(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody AnalysisDtos.Start body){return s.start(id,key,body);}
 @GetMapping("/analysis-proposals/{id}") JsonNode proposal(@PathVariable UUID id){return s.proposal(id);}
 @PostMapping("/analysis-proposals/{id}/apply") Map<String,Object> apply(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody AnalysisDtos.Apply body){return s.apply(id,key,body);}
 @PostMapping("/analysis-applications/{id}/undo") Map<String,Object> undo(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key){return s.undo(id,key);}
}
