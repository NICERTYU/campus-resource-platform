package cn.campus.resource;

import static cn.campus.resource.Models.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ResourceController {
  private final ResourceService service;

  ResourceController(ResourceService service) {
    this.service = service;
  }

  private User actor(Authentication authentication) {
    return service.byUsername(authentication.getName());
  }

  private String key(HttpServletRequest request) {
    return request.getHeader("Idempotency-Key");
  }

  @GetMapping("/classrooms")
  List<Classroom> rooms(Authentication auth) {
    return service.rooms(actor(auth));
  }

  @GetMapping("/seats/availability")
  List<Availability> availability(
      Authentication auth,
      @RequestParam long classroomId,
      @RequestParam OffsetDateTime startTime,
      @RequestParam OffsetDateTime endTime,
      @RequestParam(required = false) Boolean hasPower) {
    return service.availability(actor(auth), classroomId, startTime, endTime, hasPower);
  }

  @GetMapping("/reservations/mine")
  List<Reservation> mine(
      Authentication auth,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize,
      @RequestParam(required = false) String statusGroup) {
    return service.mine(actor(auth), page, pageSize, statusGroup);
  }

  @PostMapping("/reservations")
  Object reserve(
      Authentication auth, @RequestBody ReserveRequest body, HttpServletRequest request) {
    return service.reserve(actor(auth), body, key(request), RequestIds.of(request));
  }

  @PostMapping("/reservations/{id}/cancel")
  Object cancel(Authentication auth, @PathVariable long id, HttpServletRequest request) {
    return service.cancel(actor(auth), id, key(request), RequestIds.of(request));
  }

  record Code(String code) {}

  @PostMapping("/reservations/{id}/check-in")
  Object checkIn(
      Authentication auth,
      @PathVariable long id,
      @RequestBody Code code,
      HttpServletRequest request) {
    return service.checkIn(actor(auth), id, code.code(), key(request), RequestIds.of(request));
  }

  @GetMapping("/violations/mine")
  Object violations(
      Authentication auth,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize) {
    return service.violations(actor(auth), page, pageSize);
  }

  @GetMapping("/admin/users")
  Object users(Authentication auth, @RequestParam(required = false) String role) {
    return service.users(actor(auth), role);
  }

  @GetMapping("/admin/classrooms")
  Object adminRooms(Authentication auth) {
    service.require(actor(auth), "ADMIN");
    return service.adminRooms();
  }

  @GetMapping("/admin/classrooms/{id}/seats")
  Object adminSeats(Authentication auth, @PathVariable long id) {
    service.require(actor(auth), "ADMIN");
    return service.adminSeats(id);
  }

  @GetMapping("/admin/settings")
  Object settings(Authentication auth) {
    return service.settings(actor(auth));
  }

  record Settings(int maxReservationHours) {}

  @PutMapping("/admin/settings")
  Object settings(Authentication auth, @RequestBody Settings settings, HttpServletRequest request) {
    return service.updateSettings(
        actor(auth), settings.maxReservationHours(), key(request), RequestIds.of(request));
  }

  @PostMapping("/admin/classrooms")
  Object createRoom(
      Authentication auth, @RequestBody ClassroomInput input, HttpServletRequest request) {
    return service.createRoom(actor(auth), input, key(request), RequestIds.of(request));
  }

  @PutMapping("/admin/classrooms/{id}")
  Object updateRoom(
      Authentication auth,
      @PathVariable long id,
      @RequestBody ClassroomInput input,
      HttpServletRequest request) {
    return service.updateRoom(actor(auth), id, input, key(request), RequestIds.of(request));
  }

  @PostMapping("/admin/classrooms/{id}/seats")
  Object createSeat(
      Authentication auth,
      @PathVariable long id,
      @RequestBody SeatInput input,
      HttpServletRequest request) {
    return service.createSeat(actor(auth), id, input, key(request), RequestIds.of(request));
  }

  @PutMapping("/admin/seats/{id}")
  Object updateSeat(
      Authentication auth,
      @PathVariable long id,
      @RequestBody SeatInput input,
      HttpServletRequest request) {
    return service.updateSeat(actor(auth), id, input, key(request), RequestIds.of(request));
  }

  @GetMapping("/admin/check-in-codes")
  Object codes(Authentication auth) {
    return service.codes(actor(auth));
  }

  @PutMapping("/admin/check-in-codes/{id}")
  Object updateCode(
      Authentication auth,
      @PathVariable long id,
      @RequestBody Code code,
      HttpServletRequest request) {
    return service.updateCode(actor(auth), id, code.code(), key(request), RequestIds.of(request));
  }

  @GetMapping("/admin/audit-logs")
  Object logs(
      Authentication auth,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize) {
    return service.logs(actor(auth), page, pageSize);
  }
}
