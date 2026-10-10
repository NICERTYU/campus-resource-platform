package cn.campus.workflow;

import static cn.campus.workflow.Model.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class WorkflowRepository {
  private final JdbcTemplate db;

  WorkflowRepository(JdbcTemplate db) {
    this.db = db;
  }

  JdbcTemplate jdbc() {
    return db;
  }

  String table(String type) {
    return switch (type) {
      case "M2" -> "classroom_applications";
      case "M3" -> "repair_orders";
      default -> throw new IllegalArgumentException("Unknown source type");
    };
  }

  Map<String, Object> locked(String type, long id) {
    var rows = db.queryForList("SELECT * FROM " + table(type) + " WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "记录不存在");
    return rows.getFirst();
  }

  Object view(String type, long id) {
    if (type.equals("M2"))
      return db.queryForObject(
          "SELECT * FROM classroom_applications WHERE id=?", this::application, id);
    return db.queryForObject("SELECT * FROM repair_orders WHERE id=?", this::repair, id);
  }

  List<ApplicationView> applications(User user, int pageSize, long offset) {
    if (user.has("ADMIN"))
      return db.query(
          "SELECT * FROM classroom_applications ORDER BY id DESC LIMIT ? OFFSET ?",
          this::application,
          pageSize,
          offset);
    return db.query(
        "SELECT * FROM classroom_applications WHERE applicant_id=? ORDER BY id DESC LIMIT ? OFFSET"
            + " ?",
        this::application,
        user.id(),
        pageSize,
        offset);
  }

  List<RepairView> repairs(User user, int pageSize, long offset) {
    if (user.has("ADMIN"))
      return db.query(
          "SELECT * FROM repair_orders ORDER BY id DESC LIMIT ? OFFSET ?",
          this::repair,
          pageSize,
          offset);
    return db.query(
        "SELECT * FROM repair_orders WHERE reporter_id=? OR technician_id=? ORDER BY id DESC LIMIT"
            + " ? OFFSET ?",
        this::repair,
        user.id(),
        user.id(),
        pageSize,
        offset);
  }

  void audit(User user, String type, long id, String action, String requestId) {
    audit(user.id(), type, id, action, requestId);
  }

  void audit(long actor, String type, long id, String action, String requestId) {
    db.update(
        "INSERT INTO audit_logs(actor_id,source_type,source_id,action,request_id)"
            + " VALUES(?,?,?,?,?)",
        actor,
        type,
        id,
        action,
        requestId);
  }

  long intent(
      String type,
      long id,
      long version,
      boolean active,
      long classroom,
      OffsetDateTime start,
      OffsetDateTime end,
      User user,
      String requestId,
      String action,
      String previous,
      String target) {
    return db.queryForObject(
        """
        INSERT INTO outbox(source_type,source_id,version,active,classroom_id,start_time,end_time,
          actor_id,request_id,action,previous_status,target_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
        """,
        Long.class,
        type,
        id,
        version,
        active,
        classroom,
        start,
        end,
        user.id(),
        requestId,
        action,
        previous,
        target);
  }

  ApplicationView application(ResultSet r, int row) throws SQLException {
    return new ApplicationView(
        r.getLong("id"),
        r.getLong("classroom_id"),
        r.getString("classroom_name"),
        r.getLong("applicant_id"),
        r.getString("applicant_name"),
        time(r, "start_time"),
        time(r, "end_time"),
        r.getString("purpose"),
        r.getString("status"),
        r.getString("comment"),
        r.getString("pending_action"),
        r.getString("last_error"));
  }

  RepairView repair(ResultSet r, int row) throws SQLException {
    return new RepairView(
        r.getLong("id"),
        r.getLong("classroom_id"),
        r.getString("classroom_name"),
        r.getLong("reporter_id"),
        r.getString("reporter_name"),
        r.getObject("technician_id", Long.class),
        r.getString("technician_name"),
        r.getString("description"),
        r.getString("status"),
        r.getString("note"),
        r.getString("comment"),
        r.getString("pending_action"),
        r.getString("last_error"));
  }

  static OffsetDateTime time(ResultSet r, String field) throws SQLException {
    return r.getObject(field, OffsetDateTime.class);
  }

  static OffsetDateTime time(Map<String, Object> row, String field) {
    Object value = row.get(field);
    if (value instanceof OffsetDateTime offset) return offset;
    if (value instanceof java.sql.Timestamp timestamp)
      return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
    return null;
  }

  static long number(Map<String, Object> row, String field) {
    return ((Number) row.get(field)).longValue();
  }

  static String string(Map<String, Object> row, String field) {
    return (String) row.get(field);
  }
}
