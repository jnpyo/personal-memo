package local.personalmemo.event.api;

import java.util.List;
import local.personalmemo.event.application.EventService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {
  private static final MediaType ICALENDAR =
      MediaType.parseMediaType("text/calendar; charset=UTF-8");

  private final EventService service;

  public EventController(EventService service) {
    this.service = service;
  }

  @GetMapping
  ResponseEntity<List<EventDtos.View>> list(
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(limit));
  }

  @GetMapping("/calendar.ics")
  ResponseEntity<byte[]> calendar() {
    byte[] calendar = service.exportCalendar();
    if (calendar.length == 0) {
      return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(ICALENDAR)
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"personal-memo-calendar.ics\"")
        .body(calendar);
  }
}
