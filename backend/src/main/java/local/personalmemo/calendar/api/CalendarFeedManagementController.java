package local.personalmemo.calendar.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import local.personalmemo.calendar.api.CalendarFeedDtos.AddEvent;
import local.personalmemo.calendar.api.CalendarFeedDtos.Capabilities;
import local.personalmemo.calendar.api.CalendarFeedDtos.Create;
import local.personalmemo.calendar.api.CalendarFeedDtos.EligibleEvents;
import local.personalmemo.calendar.api.CalendarFeedDtos.EnableExternalPublication;
import local.personalmemo.calendar.api.CalendarFeedDtos.FeedDetail;
import local.personalmemo.calendar.api.CalendarFeedDtos.FeedSummary;
import local.personalmemo.calendar.api.CalendarFeedDtos.Rotate;
import local.personalmemo.calendar.api.CalendarFeedDtos.Update;
import local.personalmemo.calendar.api.CalendarFeedDtos.Versioned;
import local.personalmemo.calendar.application.CalendarFeedManagementService;
import local.personalmemo.calendar.application.CalendarFeedPublicationProperties;
import org.springframework.http.CacheControl;
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
@RequestMapping("/api/v1/calendar-feeds")
public class CalendarFeedManagementController {
  private final CalendarFeedManagementService service;
  private final CalendarFeedPublicationProperties publication;

  public CalendarFeedManagementController(
      CalendarFeedManagementService service, CalendarFeedPublicationProperties publication) {
    this.service = service;
    this.publication = publication;
  }

  @GetMapping("/capabilities")
  ResponseEntity<Capabilities> capabilities() {
    return noStore(
        new Capabilities(
            publication.enabled() ? "PUBLIC_HTTPS" : "LOCAL_ONLY",
            publication.enabled() ? publication.publicOrigin() : null,
            publication.enabled() ? publication.consentPolicyVersion() : null));
  }

  @GetMapping("/eligible-events")
  ResponseEntity<EligibleEvents> eligibleEvents() {
    return noStore(service.eligibleEvents());
  }

  @GetMapping
  ResponseEntity<List<FeedSummary>> list() {
    return noStore(service.list());
  }

  @GetMapping("/{id}")
  ResponseEntity<FeedDetail> detail(@PathVariable UUID id) {
    return noStore(service.detail(id));
  }

  @PostMapping
  ResponseEntity<FeedDetail> create(
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Create body) {
    return ResponseEntity.status(201)
        .cacheControl(CacheControl.noStore())
        .body(service.create(key, body));
  }

  @PatchMapping("/{id}")
  ResponseEntity<FeedDetail> update(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Update body) {
    return noStore(service.update(id, key, body));
  }

  @PostMapping("/{id}/rotate")
  ResponseEntity<FeedDetail> rotate(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Rotate body) {
    return noStore(service.rotate(id, key, body));
  }

  @PostMapping("/{id}/external-publication/enable")
  ResponseEntity<FeedDetail> enableExternalPublication(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody EnableExternalPublication body) {
    return noStore(service.enableExternalPublication(id, key, body));
  }

  @PostMapping("/{id}/revoke")
  ResponseEntity<FeedDetail> revoke(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Versioned body) {
    return noStore(service.revoke(id, key, body));
  }

  @PostMapping("/{id}/events")
  ResponseEntity<FeedDetail> addEvent(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AddEvent body) {
    return noStore(service.addEvent(id, key, body));
  }

  @PostMapping("/{id}/events/{entryId}/remove")
  ResponseEntity<FeedDetail> removeEvent(
      @PathVariable UUID id,
      @PathVariable UUID entryId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Versioned body) {
    return noStore(service.removeEvent(id, entryId, key, body));
  }

  private <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
