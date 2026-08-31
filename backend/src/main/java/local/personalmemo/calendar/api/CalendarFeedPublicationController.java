package local.personalmemo.calendar.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import local.personalmemo.calendar.application.CalendarFeedPublicationService;
import local.personalmemo.common.error.DomainException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/calendar/v1/feed.ics")
public class CalendarFeedPublicationController {
  private static final MediaType ICALENDAR =
      MediaType.parseMediaType("text/calendar; charset=UTF-8");

  private final CalendarFeedPublicationService service;

  public CalendarFeedPublicationController(CalendarFeedPublicationService service) {
    this.service = service;
  }

  @GetMapping
  ResponseEntity<byte[]> get(HttpServletRequest request) {
    return response(request, false);
  }

  @RequestMapping(method = RequestMethod.HEAD)
  ResponseEntity<byte[]> head(HttpServletRequest request) {
    return response(request, true);
  }

  private ResponseEntity<byte[]> response(HttpServletRequest request, boolean head) {
    try {
      String[] values = request.getParameterValues("token");
      if (request.getParameterMap().size() != 1 || values == null || values.length != 1) {
        service.read(null);
        return notFound();
      }
      Optional<byte[]> calendar;
      calendar = service.read(values[0]);
      if (calendar.isEmpty()) {
        return notFound();
      }
      if (calendar.get().length == 0) {
        return ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .build();
      }
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .header("Referrer-Policy", "no-referrer")
          .contentType(ICALENDAR)
          .contentLength(calendar.get().length)
          .body(head ? null : calendar.get());
    } catch (DomainException exception) {
      return notFound();
    }
  }

  private ResponseEntity<byte[]> notFound() {
    return ResponseEntity.notFound()
        .cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .build();
  }
}
