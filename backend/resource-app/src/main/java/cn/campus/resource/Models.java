package cn.campus.resource;

import java.time.*;
import java.util.List;

final class Models {
  private Models() {}

  record User(long id, String username, String displayName, String department, List<String> roles) {
    boolean has(String role) {
      return roles.contains(role);
    }
  }

  record Classroom(
      long id,
      String name,
      String building,
      int floor,
      String department,
      LocalTime openTime,
      LocalTime closeTime,
      boolean enabled,
      int seatCount) {}

  record Seat(long id, long classroomId, String label, boolean hasPower, boolean enabled) {}

  record Availability(
      long id,
      long classroomId,
      String label,
      boolean hasPower,
      boolean available,
      List<String> restrictionTypes) {}

  record Reservation(
      long id,
      long seatId,
      String seatLabel,
      long classroomId,
      String classroomName,
      OffsetDateTime startTime,
      OffsetDateTime endTime,
      String status,
      OffsetDateTime checkInDeadline,
      OffsetDateTime checkedInAt,
      String cancelReason) {}

  record ReserveRequest(long seatId, OffsetDateTime startTime, OffsetDateTime endTime) {}

  record ClassroomInput(
      String name,
      String building,
      int floor,
      String department,
      LocalTime openTime,
      LocalTime closeTime,
      boolean enabled) {}

  record SeatInput(String label, boolean hasPower, boolean enabled) {}

  record RestrictionInput(
      long version,
      boolean active,
      long classroomId,
      OffsetDateTime startTime,
      OffsetDateTime endTime,
      long actorId,
      String requestId) {}

  record RestrictionResult(long version, boolean active, boolean applied) {}
}
