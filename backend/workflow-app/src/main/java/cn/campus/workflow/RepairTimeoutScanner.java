package cn.campus.workflow;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RepairTimeoutScanner {
  private static final Logger LOG = LoggerFactory.getLogger(RepairTimeoutScanner.class);
  private final WorkflowRepository repo;
  private final RepairEscalationService service;
  private final Clock clock;

  RepairTimeoutScanner(WorkflowRepository repo, RepairEscalationService service, Clock clock) {
    this.repo = repo;
    this.service = service;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${workflow.repair.scan-ms:60000}",
      initialDelayString = "${workflow.repair.scan-ms:60000}")
  void scan() {
    long after = 0;
    var now = OffsetDateTime.now(clock);
    while (true) {
      var ids =
          repo.jdbc()
              .queryForList(
                  """
                  SELECT r.id FROM repair_orders r WHERE r.id>?
                  AND (r.status IN ('REPORTED','ASSIGNED','IN_PROGRESS')
                    OR (r.status='PROCESSING' AND r.pending_action='START'))
                  AND (r.repair_deadline IS NULL OR r.repair_deadline<=?)
                  AND NOT EXISTS(SELECT 1 FROM repair_escalations e WHERE e.repair_id=r.id AND e.level=1)
                  ORDER BY r.id LIMIT 100
                  """,
                  Long.class,
                  after,
                  now);
      if (ids.isEmpty()) return;
      for (long id : ids) {
        try {
          service.escalate(id);
        } catch (RuntimeException error) {
          LOG.error("M3 timeout escalation failed repairId={}", id, error);
        }
        // Advance even after a failure, so a poisoned first page cannot starve later work.
        after = id;
      }
    }
  }
}
