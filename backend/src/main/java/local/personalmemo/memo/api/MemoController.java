package local.personalmemo.memo.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import local.personalmemo.memo.application.MemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

  @GetMapping
  List<MemoDtos.View> list(
      @RequestParam(defaultValue = "ACTIVE") String status,
      @RequestParam(defaultValue = "50") int limit) {
    return service.list(status, limit);
  }

  @PatchMapping("/{id}")
  MemoDtos.View update(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody MemoDtos.Update body) {
    return service.update(id, key, body);
  }

  @DeleteMapping("/{id}")
  MemoDtos.View trash(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
    return service.trash(id, key);
  }

  @PostMapping("/{id}/restore")
  MemoDtos.View restore(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
    return service.restore(id, key);
  }
}
