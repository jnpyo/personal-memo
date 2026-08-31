package local.personalmemo.search.api;

import jakarta.validation.Valid;
import local.personalmemo.search.application.MemoSearchService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
  private final MemoSearchService service;

  public SearchController(MemoSearchService service) {
    this.service = service;
  }

  @PostMapping("/memos")
  ResponseEntity<SearchDtos.Page> memos(@Valid @RequestBody SearchDtos.Request request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(request));
  }
}
