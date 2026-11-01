package cn.campus.workflow;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ApplicationExpiryScanner {
  private static final Logger LOG = LoggerFactory.getLogger(ApplicationExpiryScanner.class);
  private final WorkflowRepository repo;
  private final WorkflowService service;
  private final Clock clock;

  ApplicationExpiryScanner(WorkflowRepository repo, WorkflowService service, Clock clock) {
    this.repo = repo;
    this.service = service;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${workflow.approval.expiry-scan-ms:5000}",
      initialDelayString = "${workflow.approval.expiry-scan-ms:5000}")
  void scan() {
    var ids =
        repo.jdbc()
            .queryForList(
                """
                SELECT id FROM classroom_applications WHERE approval_deadline<=?
                AND (status IN ('PENDING','RETURNED') OR (status='PROCESSING' AND pending_action='APPROVE'))
                ORDER BY approval_deadline,id LIMIT 100
                """,
                Long.class,
                OffsetDateTime.now(clock));
    for (long id : ids) {
      try {
        service.expireApplication(id);
      } catch (RuntimeException error) {
        LOG.error("M2 expiry failed id={}", id, error);
      }
    }
  }
}
