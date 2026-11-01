package cn.campus.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalPolicyTest {
  @Test
  void reviewerListsAndDepartmentAreEnforced() {
    var policy = new ApprovalPolicy(2, Duration.ofHours(24), "11", "12");
    var first = new Model.User(11, "first", "first", "CS", List.of("ADMIN"));
    var last = new Model.User(12, "last", "last", "ART", List.of("ADMIN"));
    assertTrue(policy.allowed(first, 1, 2, "CS"));
    assertFalse(
        policy.allowed(new Model.User(99, "other", "other", "CS", List.of("ADMIN")), 1, 2, "CS"));
    assertFalse(policy.allowed(first, 1, 2, "ART"));
    assertFalse(policy.allowed(last, 1, 2, "CS"));
    assertTrue(policy.allowed(last, 2, 2, "CS"));
    assertFalse(policy.allowed(new Model.User(12, "s", "s", "CS", List.of("STUDENT")), 2, 2, "CS"));
  }

  @Test
  void deadlineUsesEarlierOfStartAndConfiguredDuration() {
    var policy = new ApprovalPolicy(2, Duration.ofHours(24), "", "");
    var now = OffsetDateTime.parse("2026-10-26T08:00:00+08:00");
    assertEquals(now.plusHours(2), policy.deadline(now, now.plusHours(2)));
    assertEquals(now.plusHours(24), policy.deadline(now, now.plusDays(2)));
  }

  @Test
  void invalidConfigurationFailsAtStartup() {
    assertThrows(
        IllegalArgumentException.class, () -> new ApprovalPolicy(3, Duration.ofHours(24), "", ""));
    assertThrows(
        IllegalArgumentException.class, () -> new ApprovalPolicy(2, Duration.ZERO, "", ""));
    assertThrows(
        IllegalArgumentException.class, () -> new ApprovalPolicy(2, Duration.ofHours(24), "0", ""));
  }
}
