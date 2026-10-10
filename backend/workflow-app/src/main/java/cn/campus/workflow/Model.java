package cn.campus.workflow;

import java.time.OffsetDateTime;
import java.util.List;

final class Model {
  private Model() {}

  record User(long id, String username, String displayName, String department, List<String> roles) {
    boolean has(String role) {
      return roles != null && roles.contains(role);
    }
  }

  record Classroom(
      long id,
      String name,
      String building,
      int floor,
      String department,
      String openTime,
      String closeTime,
      boolean enabled,
      int seatCount) {}

  record ApplicationInput(
      Long classroomId, OffsetDateTime startTime, OffsetDateTime endTime, String purpose) {}

  record RepairInput(Long classroomId, String description) {}

  record CommentInput(String comment) {}

  record AssignmentInput(Long technicianId) {}

  record CompletionInput(String note) {}

  record ApplicationView(
      long id,
      long classroomId,
      String classroomName,
      long applicantId,
      String applicantName,
      OffsetDateTime startTime,
      OffsetDateTime endTime,
      String purpose,
      String status,
      String comment,
      String pendingAction,
      String lastError) {}

  record RepairView(
      long id,
      long classroomId,
      String classroomName,
      long reporterId,
      String reporterName,
      Long technicianId,
      String technicianName,
      String description,
      String status,
      String note,
      String comment,
      String pendingAction,
      String lastError) {}

  record Restriction(
      long version,
      boolean active,
      long classroomId,
      OffsetDateTime startTime,
      OffsetDateTime endTime,
      long actorId,
      String requestId) {}

  record RestrictionAck(long version, boolean active, boolean applied) {}

  record Error(String code, String message, String requestId) {}

  record Result(int status, String json) {}

  record Mutation(String type, long id, Long outboxId) {}

  record Intent(
      long id,
      String type,
      long sourceId,
      long version,
      boolean active,
      long classroomId,
      OffsetDateTime start,
      OffsetDateTime end,
      long actorId,
      String requestId,
      String action,
      String previousStatus,
      String targetStatus,
      String attemptToken) {}
}
