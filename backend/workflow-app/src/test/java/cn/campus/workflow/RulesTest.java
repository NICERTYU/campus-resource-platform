package cn.campus.workflow;

import static cn.campus.workflow.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulesTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T01:00:00Z"), ZoneOffset.UTC);
  private final Classroom room = new Classroom(1, "A101", "A", 1, null, "08:00", "22:00", true, 20);

  @Test
  void classroomApplicationsUseMinutePrecisionWithinCampusOpeningHours() {
    assertDoesNotThrow(
        () ->
            Rules.applicationTime(
                time("2026-10-11T09:15:00+08:00"), time("2026-10-11T10:25:00+08:00"), room, clock));
    assertThrows(
        ApiException.class,
        () ->
            Rules.applicationTime(
                time("2026-10-11T09:15:01+08:00"), time("2026-10-11T10:25:00+08:00"), room, clock));
    assertThrows(
        ApiException.class,
        () ->
            Rules.applicationTime(
                time("2026-10-11T07:59:00+08:00"), time("2026-10-11T10:00:00+08:00"), room, clock));
    assertThrows(
        ApiException.class,
        () ->
            Rules.applicationTime(
                time("2026-10-11T21:00:00+08:00"), time("2026-10-12T09:00:00+08:00"), room, clock));
    assertThrows(
        ApiException.class,
        () ->
            Rules.applicationTime(
                time("2026-10-10T09:00:00+08:00"), time("2026-10-10T10:00:00+08:00"), room, clock));
  }

  @Test
  void departmentRestrictionsApplyExceptToAdministrators() {
    User student = new User(1, "s", "学生", "CS", List.of("STUDENT"));
    Classroom restricted = new Classroom(2, "B101", "B", 1, "Math", "08:00", "22:00", true, 20);
    assertDoesNotThrow(() -> Rules.classroomAccess(student, room));
    assertThrows(ApiException.class, () -> Rules.classroomAccess(student, restricted));
    assertDoesNotThrow(
        () -> Rules.classroomAccess(new User(3, "a", "管理", "CS", List.of("ADMIN")), restricted));
    assertThrows(ApiException.class, () -> Rules.text(" ", 100, true));
    assertEquals("故障", Rules.text(" 故障 ", 100, true));
  }

  private static OffsetDateTime time(String text) {
    return OffsetDateTime.parse(text);
  }
}
