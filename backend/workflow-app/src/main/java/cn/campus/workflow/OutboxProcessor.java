package cn.campus.workflow;

import static cn.campus.workflow.Model.*;
import static cn.campus.workflow.WorkflowRepository.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class OutboxProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxProcessor.class);
  private final WorkflowRepository repo;
  private final TransactionTemplate tx;
  private final ResourceClient resource;
  private final Clock clock;

  OutboxProcessor(
      WorkflowRepository repo, TransactionTemplate tx, ResourceClient resource, Clock clock) {
    this.repo = repo;
    this.tx = tx;
    this.resource = resource;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${workflow.retry-delay-ms:5000}",
      initialDelayString = "${workflow.retry-delay-ms:5000}")
  void retry() {
    var ids =
        repo.jdbc()
            .queryForList(
                """
                SELECT id FROM outbox WHERE (status='PENDING' AND due_at<=CURRENT_TIMESTAMP)
                    OR (status='SENDING' AND lease_until<CURRENT_TIMESTAMP) ORDER BY id LIMIT 100
                """,
                Long.class);
    for (long id : ids) {
      try {
        process(id);
      } catch (RuntimeException e) {
        LOG.error("Outbox retry failure id={}", id, e);
      }
    }
  }

  void process(long id) {
    Intent intent =
        tx.execute(
            status -> {
              var rows =
                  repo.jdbc()
                      .queryForList(
                          """
                          SELECT * FROM outbox WHERE id=? AND (status='PENDING' OR
                            (status='SENDING' AND lease_until<CURRENT_TIMESTAMP)) FOR UPDATE SKIP LOCKED
                          """,
                          id);
              if (rows.isEmpty()) return null;
              var row = rows.getFirst();
              String token = UUID.randomUUID().toString();
              repo.jdbc()
                  .update(
                      "UPDATE outbox SET"
                          + " status='SENDING',attempt_token=?::uuid,lease_until=CURRENT_TIMESTAMP+INTERVAL"
                          + " '10 seconds',attempts=attempts+1 WHERE id=?",
                      token,
                      id);
              return new Intent(
                  id,
                  string(row, "source_type"),
                  number(row, "source_id"),
                  number(row, "version"),
                  (Boolean) row.get("active"),
                  number(row, "classroom_id"),
                  time(row, "start_time"),
                  time(row, "end_time"),
                  number(row, "actor_id"),
                  string(row, "request_id"),
                  string(row, "action"),
                  string(row, "previous_status"),
                  string(row, "target_status"),
                  token);
            });
    if (intent == null) return;
    // This call is deliberately outside both local transactions. A lost response is retried with
    // the same version.
    try {
      RestrictionAck ack = resource.restriction(intent);
      if (ack.version() != intent.version() || ack.active() != intent.active())
        throw new ApiException(409, "STALE_RESTRICTION", "资源限制版本发生冲突，请刷新后重试");
      finish(intent, null);
    } catch (ApiException e) {
      finish(intent, e);
    }
  }

  private void finish(Intent intent, ApiException error) {
    tx.executeWithoutResult(
        status -> {
          var rows =
              repo.jdbc()
                  .queryForList(
                      "SELECT id FROM outbox WHERE id=? AND status='SENDING' AND"
                          + " attempt_token=?::uuid FOR UPDATE",
                      intent.id(),
                      intent.attemptToken());
          if (rows.isEmpty()) return;
          String table = repo.table(intent.type());
          if (error != null && error.status >= 500) {
            repo.jdbc()
                .update(
                    "UPDATE outbox SET"
                        + " status='PENDING',due_at=?,lease_until=NULL,last_error=?,error_code=?,error_status=?"
                        + " WHERE id=?",
                    OffsetDateTime.ofInstant(clock.instant().plusSeconds(5), ZoneOffset.UTC),
                    limit(error.getMessage()),
                    error.code,
                    error.status,
                    intent.id());
            repo.jdbc()
                .update(
                    "UPDATE "
                        + table
                        + " SET last_error=? WHERE id=? AND source_version=? AND"
                        + " status='PROCESSING'",
                    limit(error.getMessage()),
                    intent.sourceId(),
                    intent.version());
            return;
          }
          String target = error == null ? intent.targetStatus() : intent.previousStatus();
          int changed =
              repo.jdbc()
                  .update(
                      "UPDATE "
                          + table
                          + " SET status=?,pending_action=NULL,last_error=? WHERE id=? AND"
                          + " source_version=? AND status='PROCESSING' AND pending_action=?",
                      target,
                      error == null ? null : limit(error.getMessage()),
                      intent.sourceId(),
                      intent.version(),
                      intent.action());
          if (changed != 1)
            throw new IllegalStateException("Outbox no longer matches its business record");
          repo.jdbc()
              .update(
                  "UPDATE outbox SET"
                      + " status=?,lease_until=NULL,last_error=?,error_code=?,error_status=? WHERE"
                      + " id=?",
                  error == null ? "DONE" : "REJECTED",
                  error == null ? null : limit(error.getMessage()),
                  error == null ? null : error.code,
                  error == null ? null : error.status,
                  intent.id());
          if (error == null)
            repo.audit(
                intent.actorId(),
                intent.type(),
                intent.sourceId(),
                intent.action(),
                intent.requestId());
          LOG.info(
              "Restriction result requestId={} source={}/{} version={} result={}",
              intent.requestId(),
              intent.type(),
              intent.sourceId(),
              intent.version(),
              error == null ? "DONE" : "REJECTED");
        });
  }

  private static String limit(String message) {
    return message.substring(0, Math.min(1000, message.length()));
  }
}
