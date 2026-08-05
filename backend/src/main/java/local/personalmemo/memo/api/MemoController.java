package local.personalmemo.memo.api;
import jakarta.validation.Valid; import java.net.URI; import java.util.UUID; import local.personalmemo.memo.application.MemoService; import org.springframework.http.*; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/memos") public class MemoController {
  private final MemoService service; public MemoController(MemoService service){this.service=service;}
  @PostMapping ResponseEntity<MemoDtos.View> create(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MemoDtos.Create body){var v=service.create(key,body);return ResponseEntity.created(URI.create("/api/v1/memos/"+v.id())).body(v);}
  @GetMapping("/{id}") MemoDtos.View get(@PathVariable UUID id){return service.get(id);}
  @PatchMapping("/{id}") MemoDtos.View update(@PathVariable UUID id,@Valid @RequestBody MemoDtos.Update body){return service.update(id,body);}
}
