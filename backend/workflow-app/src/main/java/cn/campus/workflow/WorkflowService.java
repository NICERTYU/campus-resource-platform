package cn.campus.workflow;

import static cn.campus.workflow.Model.*;
import static cn.campus.workflow.WorkflowRepository.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
class WorkflowService {
  private final WorkflowRepository repo;
  private final ResourceClient resource;
  private final OutboxProcessor outbox;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final Clock clock;

  WorkflowService(
      WorkflowRepository repo,
      ResourceClient resource,
      OutboxProcessor outbox,
      TransactionTemplate tx,
      ObjectMapper json,
      Clock clock) {
    this.repo = repo;
    this.resource = resource;
    this.outbox = outbox;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  List<ApplicationView> applications(User user) {
    return applications(user, 1, 50);
  }

  List<ApplicationView> applications(User user, int page, int pageSize) {
    return repo.applications(user, pageSize, Rules.pageOffset(page, pageSize));
  }

  List<RepairView> repairs(User user) {
    return repairs(user, 1, 50);
  }

  List<RepairView> repairs(User user, int page, int pageSize) {
    return repo.repairs(user, pageSize, Rules.pageOffset(page, pageSize));
  }

  Result createApplication(User user, String key, ApplicationInput input, String requestId) {
    if (!user.has("APPLICANT") && !user.has("ADMIN")) throw ApiException.forbidden();
    Result replay = replay(user, "M2:create", key, input);
    if (replay != null) return replay;
    if (input.classroomId() == null || input.classroomId() <= 0) throw ApiException.bad("请选择教室");
    Classroom room = resource.classroom(input.classroomId(), requestId);
    Rules.classroomAccess(user, room);
    Rules.applicationTime(input.startTime(), input.endTime(), room, clock);
    String purpose = Rules.text(input.purpose(), 1000, true);
    return write(
        user,
        "M2:create",
        key,
        input,
        requestId,
        true,
        () -> {
          long id =
              repo.jdbc()
                  .queryForObject(
                      """
                      INSERT INTO classroom_applications(classroom_id,classroom_name,applicant_id,applicant_name,
                        start_time,end_time,purpose,status) VALUES(?,?,?,?,?,?,?,'PENDING') RETURNING id
                      """,
                      Long.class,
                      room.id(),
                      room.name(),
                      user.id(),
                      user.displayName(),
                      input.startTime(),
                      input.endTime(),
                      purpose);
          repo.audit(user, "M2", id, "CREATE", requestId);
          return new Mutation("M2", id, null);
        });
  }

  Result applicationAction(
      User user, long id, String action, String key, CommentInput input, String requestId) {
    if (!action.equals("withdraw")) Rules.admin(user);
    Result replay = replay(user, "M2:" + id + ":" + action, key, input == null ? Map.of() : input);
    if (replay != null) return replay;
    String comment = Rules.text(input == null ? null : input.comment(), 1000, false);
    return write(
        user,
        "M2:" + id + ":" + action,
        key,
        input == null ? Map.of() : input,
        requestId,
        false,
        () -> {
          var row = repo.locked("M2", id);
          String current = string(row, "status");
          if (action.equals("withdraw")
              && !user.has("ADMIN")
              && number(row, "applicant_id") != user.id()) throw ApiException.forbidden();
          if (current.equals("PROCESSING")) throw ApiException.conflict("该申请正在同步资源状态，请稍后重试");
          switch (action) {
            case "reject" -> {
              require(current, "PENDING");
              repo.jdbc()
                  .update(
                      "UPDATE classroom_applications SET"
                          + " status='REJECTED',comment=?,last_error=NULL WHERE id=?",
                      comment,
                      id);
              repo.audit(user, "M2", id, "REJECT", requestId);
              return new Mutation("M2", id, null);
            }
            case "approve" -> {
              require(current, "PENDING");
              if (!time(row, "start_time").toInstant().isAfter(clock.instant()))
                throw ApiException.conflict("申请开始时间已到，不能再审批");
              repo.jdbc()
                  .update("UPDATE classroom_applications SET comment=? WHERE id=?", comment, id);
              return restriction("M2", id, row, true, user, requestId, "APPROVE", "APPROVED");
            }
            case "withdraw" -> {
              if (!current.equals("PENDING") && !current.equals("APPROVED"))
                throw ApiException.conflict("当前状态不能撤销申请");
              if (current.equals("APPROVED")) {
                if (!time(row, "start_time").toInstant().isAfter(clock.instant()))
                  throw ApiException.conflict("已批准申请只能在开始前撤销");
                return restriction("M2", id, row, false, user, requestId, "WITHDRAW", "WITHDRAWN");
              }
              repo.jdbc()
                  .update(
                      "UPDATE classroom_applications SET status='WITHDRAWN',last_error=NULL WHERE"
                          + " id=?",
                      id);
              repo.audit(user, "M2", id, "WITHDRAW", requestId);
              return new Mutation("M2", id, null);
            }
            default -> throw ApiException.bad("未知申请操作");
          }
        });
  }

  Result createRepair(User user, String key, RepairInput input, String requestId) {
    Result replay = replay(user, "M3:create", key, input);
    if (replay != null) return replay;
    if (input.classroomId() == null || input.classroomId() <= 0) throw ApiException.bad("请选择教室");
    Classroom room = resource.classroom(input.classroomId(), requestId);
    Rules.classroomAccess(user, room);
    String description = Rules.text(input.description(), 2000, true);
    return write(
        user,
        "M3:create",
        key,
        input,
        requestId,
        true,
        () -> {
          long id =
              repo.jdbc()
                  .queryForObject(
                      """
                      INSERT INTO repair_orders(classroom_id,classroom_name,reporter_id,reporter_name,description,status)
                      VALUES(?,?,?,?,?,'REPORTED') RETURNING id
                      """,
                      Long.class,
                      room.id(),
                      room.name(),
                      user.id(),
                      user.displayName(),
                      description);
          repo.audit(user, "M3", id, "CREATE", requestId);
          return new Mutation("M3", id, null);
        });
  }

  Result assign(User user, long id, String key, AssignmentInput input, String requestId) {
    Rules.admin(user);
    Result replay = replay(user, "M3:" + id + ":assign", key, input);
    if (replay != null) return replay;
    if (input.technicianId() == null) throw ApiException.bad("请选择维修人员");
    User technician =
        resource.users(requestId).stream()
            .filter(u -> u.id() == input.technicianId() && u.has("TECHNICIAN"))
            .findFirst()
            .orElseThrow(() -> ApiException.bad("指定用户没有维修人员资格"));
    return write(
        user,
        "M3:" + id + ":assign",
        key,
        input,
        requestId,
        false,
        () -> {
          var row = repo.locked("M3", id);
          String current = string(row, "status");
          if (!current.equals("REPORTED") && !current.equals("ASSIGNED"))
            throw ApiException.conflict("只可分派待派单或已派单工单");
          repo.jdbc()
              .update(
                  "UPDATE repair_orders SET"
                      + " status='ASSIGNED',technician_id=?,technician_name=?,last_error=NULL WHERE"
                      + " id=?",
                  technician.id(),
                  technician.displayName(),
                  id);
          repo.audit(user, "M3", id, "ASSIGN", requestId);
          return new Mutation("M3", id, null);
        });
  }

  Result repairAction(
      User user, long id, String action, String key, Object input, String requestId) {
    if (action.equals("accept") || action.equals("return")) Rules.admin(user);
    Result replay = replay(user, "M3:" + id + ":" + action, key, input == null ? Map.of() : input);
    if (replay != null) return replay;
    String note =
        input instanceof CompletionInput completion
            ? Rules.text(completion.note(), 2000, true)
            : null;
    String comment = input instanceof CommentInput c ? Rules.text(c.comment(), 1000, false) : null;
    return write(
        user,
        "M3:" + id + ":" + action,
        key,
        input == null ? Map.of() : input,
        requestId,
        false,
        () -> {
          var row = repo.locked("M3", id);
          String current = string(row, "status");
          if (action.equals("start") || action.equals("complete")) {
            Object technician = row.get("technician_id");
            if (!user.has("TECHNICIAN")
                || technician == null
                || ((Number) technician).longValue() != user.id()) throw ApiException.forbidden();
          }
          switch (action) {
            case "start" -> {
              require(current, "ASSIGNED");
              return restriction("M3", id, row, true, user, requestId, "START", "IN_PROGRESS");
            }
            case "complete" -> {
              require(current, "IN_PROGRESS");
              repo.jdbc()
                  .update(
                      "UPDATE repair_orders SET status='AWAITING_ACCEPTANCE',note=?,last_error=NULL"
                          + " WHERE id=?",
                      note,
                      id);
              repo.audit(user, "M3", id, "COMPLETE", requestId);
              return new Mutation("M3", id, null);
            }
            case "accept" -> {
              require(current, "AWAITING_ACCEPTANCE");
              repo.jdbc().update("UPDATE repair_orders SET comment=? WHERE id=?", comment, id);
              return restriction("M3", id, row, false, user, requestId, "ACCEPT", "ACCEPTED");
            }
            case "return" -> {
              require(current, "AWAITING_ACCEPTANCE");
              repo.jdbc()
                  .update(
                      "UPDATE repair_orders SET status='IN_PROGRESS',comment=?,last_error=NULL"
                          + " WHERE id=?",
                      comment,
                      id);
              repo.audit(user, "M3", id, "RETURN", requestId);
              return new Mutation("M3", id, null);
            }
            default -> throw ApiException.bad("未知工单操作");
          }
        });
  }

  private Mutation restriction(
      String type,
      long id,
      Map<String, Object> row,
      boolean active,
      User user,
      String requestId,
      String action,
      String target) {
    long version = number(row, "source_version") + 1;
    OffsetDateTime start =
        type.equals("M2")
            ? time(row, "start_time")
            : active ? null : time(row, "restriction_start");
    if (start == null) start = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    OffsetDateTime end = type.equals("M2") ? time(row, "end_time") : null;
    repo.jdbc()
        .update(
            "UPDATE "
                + repo.table(type)
                + " SET status='PROCESSING',pending_action=?,source_version=?,last_error=NULL WHERE"
                + " id=?",
            action,
            version,
            id);
    if (type.equals("M3"))
      repo.jdbc().update("UPDATE repair_orders SET restriction_start=? WHERE id=?", start, id);
    long intent =
        repo.intent(
            type,
            id,
            version,
            active,
            number(row, "classroom_id"),
            start,
            end,
            user,
            requestId,
            action,
            string(row, "status"),
            target);
    return new Mutation(type, id, intent);
  }

  private static void require(String actual, String expected) {
    if (!actual.equals(expected)) throw ApiException.conflict("当前状态不允许执行该操作");
  }

  private record WriteState(String type, long id, Long outboxId, Result replay) {}

  private Result replay(User user, String action, String key, Object input) {
    if (key == null || key.isBlank()) return null;
    if (key.length() > 128) throw ApiException.bad("Idempotency-Key 最多128字符");
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT signature,response_json,response_status FROM idempotency_results WHERE"
                    + " actor_id=? AND action=? AND request_key=?",
                user.id(),
                action,
                key);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    if (!digest(json.writeValueAsString(input)).equals(string(row, "signature")))
      throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "相同请求键不能用于不同内容");
    return row.get("response_json") == null
        ? null
        : new Result(
            ((Number) row.get("response_status")).intValue(), string(row, "response_json"));
  }

  private Result write(
      User user,
      String action,
      String suppliedKey,
      Object input,
      String requestId,
      boolean creation,
      Supplier<Mutation> operation) {
    String key =
        suppliedKey == null || suppliedKey.isBlank() ? UUID.randomUUID().toString() : suppliedKey;
    if (key.length() > 128) throw ApiException.bad("Idempotency-Key 最多128字符");
    String signature = digest(json.writeValueAsString(input));
    WriteState state =
        tx.execute(
            status -> {
              lock(user.id(), action, key);
              var existing =
                  repo.jdbc()
                      .queryForList(
                          "SELECT * FROM idempotency_results WHERE actor_id=? AND action=? AND"
                              + " request_key=?",
                          user.id(),
                          action,
                          key);
              if (!existing.isEmpty()) {
                var row = existing.getFirst();
                if (!signature.equals(string(row, "signature")))
                  throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "相同请求键不能用于不同内容");
                Result replay =
                    row.get("response_json") == null
                        ? null
                        : new Result(
                            ((Number) row.get("response_status")).intValue(),
                            string(row, "response_json"));
                return new WriteState(
                    string(row, "source_type"),
                    number(row, "source_id"),
                    row.get("outbox_id") == null ? null : number(row, "outbox_id"),
                    replay);
              }
              Mutation mutation = operation.get();
              repo.jdbc()
                  .update(
                      """
                      INSERT INTO idempotency_results(actor_id,action,request_key,signature,source_type,source_id,outbox_id)
                      VALUES(?,?,?,?,?,?,?)
                      """,
                      user.id(),
                      action,
                      key,
                      signature,
                      mutation.type(),
                      mutation.id(),
                      mutation.outboxId());
              return new WriteState(mutation.type(), mutation.id(), mutation.outboxId(), null);
            });
    if (state.replay() != null) return state.replay();
    if (state.outboxId() != null) outbox.process(state.outboxId());
    return tx.execute(
        status -> {
          lock(user.id(), action, key);
          var row =
              repo.jdbc()
                  .queryForMap(
                      "SELECT * FROM idempotency_results WHERE actor_id=? AND action=? AND"
                          + " request_key=?",
                      user.id(),
                      action,
                      key);
          if (row.get("response_json") != null)
            return new Result(
                ((Number) row.get("response_status")).intValue(), string(row, "response_json"));
          int httpStatus = creation ? 201 : 200;
          Object body = repo.view(state.type(), state.id());
          if (state.outboxId() != null) {
            var intent =
                repo.jdbc().queryForMap("SELECT * FROM outbox WHERE id=?", state.outboxId());
            if (string(intent, "status").equals("REJECTED")) {
              httpStatus = ((Number) intent.get("error_status")).intValue();
              body =
                  new Model.Error(
                      string(intent, "error_code"), string(intent, "last_error"), requestId);
            } else if (!string(intent, "status").equals("DONE")) httpStatus = 202;
          }
          String response = json.writeValueAsString(body);
          repo.jdbc()
              .update(
                  "UPDATE idempotency_results SET response_json=?,response_status=? WHERE"
                      + " actor_id=? AND action=? AND request_key=?",
                  response,
                  httpStatus,
                  user.id(),
                  action,
                  key);
          return new Result(httpStatus, response);
        });
  }

  private void lock(long actor, String action, String key) {
    repo.jdbc()
        .queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
            Object.class,
            actor + ":" + action + ":" + key);
  }

  private static String digest(String body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
