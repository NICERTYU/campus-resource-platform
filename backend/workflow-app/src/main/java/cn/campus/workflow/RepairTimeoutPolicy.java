package cn.campus.workflow;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class RepairTimeoutPolicy {
  final Duration timeout;

  RepairTimeoutPolicy(
      @Value("${workflow.repair.timeout:PT24H}") Duration timeout,
      @Value("${workflow.repair.scan-ms:60000}") long scanMs) {
    if (timeout.isNegative() || timeout.isZero() || scanMs <= 0)
      throw new IllegalArgumentException("Repair timeout and scan interval must be positive");
    this.timeout = timeout;
  }

  OffsetDateTime deadline(OffsetDateTime createdAt) {
    return createdAt.plus(timeout);
  }

  static boolean eligible(String status, String pendingAction) {
    return java.util.Set.of("REPORTED", "ASSIGNED", "IN_PROGRESS").contains(status)
        || ("PROCESSING".equals(status) && "START".equals(pendingAction));
  }
}
