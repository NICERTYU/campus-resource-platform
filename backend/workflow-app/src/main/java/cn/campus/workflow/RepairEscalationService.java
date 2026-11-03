package cn.campus.workflow;

import static cn.campus.workflow.WorkflowRepository.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RepairEscalationService {
  private static final Logger LOG = LoggerFactory.getLogger(RepairEscalationService.class);
  private final WorkflowRepository repo;
  private final TransactionTemplate tx;
  private final RepairTimeoutPolicy policy;
  private final Clock clock;

  RepairEscalationService(
      WorkflowRepository repo, TransactionTemplate tx, RepairTimeoutPolicy policy, Clock clock) {
    this.repo = repo;
    this.tx = tx;
    this.policy = policy;
    this.clock = clock;
  }

  boolean escalate(long id) {
    String requestId = UUID.randomUUID().toString();
    boolean changed =
        Boolean.TRUE.equals(
            tx.execute(
                status -> {
                  // The same row lock is used by completion, reassignment and acceptance.
                  var row = repo.locked("M3", id);
                  String current = string(row, "status");
                  if (!RepairTimeoutPolicy.eligible(current, string(row, "pending_action")))
                    return false;
                  var deadline = time(row, "repair_deadline");
                  if (deadline == null) {
                    deadline = policy.deadline(time(row, "created_at"));
                    repo.jdbc()
                        .update(
                            "UPDATE repair_orders SET repair_deadline=? WHERE id=?", deadline, id);
                  }
                  var now = OffsetDateTime.now(clock);
                  if (deadline.isAfter(now)) return false;
                  // This record is also the durable administrator inbox event, not a delivery
                  // receipt.
                  return repo.jdbc()
                          .update(
                              """
                              INSERT INTO repair_escalations(repair_id,level,observed_status,deadline,escalated_at,request_id)
                              VALUES(?,1,?,?,?,?) ON CONFLICT(repair_id,level) DO NOTHING
                              """,
                              id,
                              current,
                              deadline,
                              now,
                              requestId)
                      == 1;
                }));
    if (changed) LOG.info("Repair escalated repairId={} level=1 requestId={}", id, requestId);
    return changed;
  }

  java.util.List<Model.RepairEscalation> list(Model.User user, int page, int pageSize) {
    Rules.admin(user);
    long offset = Rules.pageOffset(page, pageSize);
    return repo.jdbc()
        .query(
            "SELECT * FROM repair_escalations ORDER BY escalated_at DESC,id DESC LIMIT ? OFFSET ?",
            (r, n) ->
                new Model.RepairEscalation(
                    r.getLong("id"),
                    r.getLong("repair_id"),
                    r.getInt("level"),
                    r.getString("observed_status"),
                    time(r, "deadline"),
                    time(r, "escalated_at"),
                    r.getString("request_id"),
                    r.getString("notification_status")),
            pageSize,
            offset);
  }
}
