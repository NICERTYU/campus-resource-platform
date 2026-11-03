package cn.campus.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class RepairTimeoutPolicyTest {
  @Test
  void positiveConfigurationDeterminesDeadlineAcrossOffsetsAndMidnight() {
    var start = OffsetDateTime.parse("2026-11-03T23:30:00+08:00");
    assertEquals(
        start.plusHours(2), new RepairTimeoutPolicy(Duration.ofHours(2), 1000).deadline(start));
    assertThrows(
        IllegalArgumentException.class, () -> new RepairTimeoutPolicy(Duration.ZERO, 1000));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RepairTimeoutPolicy(Duration.ofSeconds(-1), 1000));
    assertThrows(
        IllegalArgumentException.class, () -> new RepairTimeoutPolicy(Duration.ofHours(1), 0));
  }

  @Test
  void onlyUnfinishedRepairWorkIsEligibleIncludingPendingStart() {
    for (String status : java.util.List.of("REPORTED", "ASSIGNED", "IN_PROGRESS"))
      assertTrue(RepairTimeoutPolicy.eligible(status, null));
    assertTrue(RepairTimeoutPolicy.eligible("PROCESSING", "START"));
    assertFalse(RepairTimeoutPolicy.eligible("PROCESSING", "ACCEPT"));
    assertFalse(RepairTimeoutPolicy.eligible("AWAITING_ACCEPTANCE", null));
    assertFalse(RepairTimeoutPolicy.eligible("ACCEPTED", null));
  }
}
