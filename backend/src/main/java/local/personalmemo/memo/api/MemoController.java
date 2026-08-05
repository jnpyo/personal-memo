package local.personalmemo.memo.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import local.personalmemo.memo.application.MemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/memos")
public class MemoController {
  private final MemoService service;

  public MemoController(MemoService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<MemoDtos.View> create(
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody MemoDtos.Create body) {
    MemoDtos.View memo = service.create(key, body);
    return ResponseEntity.created(URI.create("/api/v1/memos/" + memo.id())).body(memo);
  }

  @GetMapping("/{id}")
  MemoDtos.View get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PatchMapping("/{id}")
  MemoDtos.View update(@PathVariable UUID id, @Valid @RequestBody MemoDtos.Update body) {
    return service.update(id, body);
  }
}
