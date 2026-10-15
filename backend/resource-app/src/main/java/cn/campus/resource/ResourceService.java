package cn.campus.resource;

import static cn.campus.resource.Models.*;

import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.*;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
class ResourceService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ObjectMapper json;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

  ResourceService(
      JdbcTemplate db, PlatformTransactionManager manager, Clock clock, ObjectMapper json) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.clock = clock;
    this.json = json;
  }

  Instant now() {
    return clock.instant();
  }

  private Timestamp stamp(Instant value) {
    return Timestamp.from(value);
  }

  private Timestamp stamp(OffsetDateTime value) {
    return value == null ? null : stamp(value.toInstant());
  }

  private OffsetDateTime offset(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class);
  }

  private <T> T required(List<T> values) {
    if (values.isEmpty()) throw ApiError.missing();
    return values.getFirst();
  }

  Map<String, Object> loginUser(String username) {
    var rows =
        db.queryForList(
            "SELECT id,password_hash,enabled FROM users WHERE lower(username)=lower(?)", username);
    if (rows.isEmpty()) throw new UsernameNotFoundException("Unknown user");
    return rows.getFirst();
  }

  User byUsername(String username) {
    return user(((Number) loginUser(username).get("id")).longValue());
  }

  User user(long id) {
    return required(
        db.query(
            "SELECT id,username,display_name,department,enabled FROM users WHERE id=?",
            (rs, n) -> {
              if (!rs.getBoolean("enabled")) throw ApiError.forbidden();
              return new User(
                  rs.getLong("id"),
                  rs.getString("username"),
                  rs.getString("display_name"),
                  rs.getString("department"),
                  db.queryForList(
                      "SELECT role FROM user_roles WHERE user_id=? ORDER BY role",
                      String.class,
                      id));
            },
            id));
  }

  List<User> users(User actor) {
    return users(actor, null);
  }

  List<User> users(User actor, String role) {
    require(actor, "ADMIN");
    return allUsers(role);
  }

  List<User> allUsers() {
    return allUsers(null);
  }

  List<User> allUsers(String role) {
    if (role != null && !Set.of("STUDENT", "APPLICANT", "ADMIN", "TECHNICIAN").contains(role))
      throw ApiError.bad("角色不正确");
    if (role != null)
      return db
          .queryForList(
              "SELECT u.id FROM users u WHERE u.enabled AND EXISTS"
                  + " (SELECT 1 FROM user_roles r WHERE r.user_id=u.id AND r.role=?) ORDER BY u.id",
              Long.class,
              role)
          .stream()
          .map(this::user)
          .toList();
    return db
        .queryForList("SELECT id FROM users WHERE enabled ORDER BY id LIMIT 200", Long.class)
        .stream()
        .map(this::user)
        .toList();
  }

  void require(User actor, String role) {
    if (!actor.has(role)) throw ApiError.forbidden();
  }

  void access(User actor, Classroom room) {
    if (!room.enabled()) throw ApiError.conflict("教室已停用");
    if (room.department() != null
        && !room.department().isBlank()
        && !room.department().equals(actor.department())
        && !actor.has("ADMIN")) throw ApiError.forbidden();
  }

  private RowMapper<Classroom> roomMapper() {
    return (rs, n) ->
        new Classroom(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("building"),
            rs.getInt("floor"),
            rs.getString("department"),
            rs.getObject("open_time", LocalTime.class),
            rs.getObject("close_time", LocalTime.class),
            rs.getBoolean("enabled"),
            rs.getInt("seat_count"));
  }

  private String roomQuery() {
    return "SELECT c.*,(SELECT count(*) FROM seats s WHERE s.classroom_id=c.id)::int seat_count"
        + " FROM classrooms c ";
  }

  Classroom room(long id) {
    return required(db.query(roomQuery() + "WHERE c.id=?", roomMapper(), id));
  }

  List<Classroom> adminRooms() {
    return db.query(roomQuery() + "ORDER BY c.id LIMIT 200", roomMapper());
  }

  List<Seat> adminSeats(long room) {
    room(room);
    return db.query(
        "SELECT * FROM seats WHERE classroom_id=? ORDER BY id LIMIT 200",
        (rs, n) ->
            new Seat(
                rs.getLong("id"),
                rs.getLong("classroom_id"),
                rs.getString("label"),
                rs.getBoolean("has_power"),
                rs.getBoolean("enabled")),
        room);
  }

  List<Classroom> rooms(User actor) {
    return db.query(
        roomQuery()
            + "WHERE c.enabled AND (c.department IS NULL OR c.department='' OR c.department=? OR ?)"
            + " ORDER BY c.id LIMIT 200",
        roomMapper(),
        actor.department(),
        actor.has("ADMIN"));
  }

  private Seat seat(long id) {
    return required(
        db.query(
            "SELECT * FROM seats WHERE id=?",
            (rs, n) ->
                new Seat(
                    rs.getLong("id"),
                    rs.getLong("classroom_id"),
                    rs.getString("label"),
                    rs.getBoolean("has_power"),
                    rs.getBoolean("enabled")),
            id));
  }

  private void lockRoom(long id) {
    if (db.queryForList("SELECT id FROM classrooms WHERE id=? FOR UPDATE", Long.class, id)
        .isEmpty()) throw ApiError.missing();
  }

  private void advisory(String key) {
    db.execute(
        (ConnectionCallback<Void>)
            c -> {
              try (var s =
                  c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                s.setString(1, key);
                s.execute();
              }
              return null;
            });
  }

  private void audit(Long actor, String action, String type, Long id, String requestId) {
    db.update(
        "INSERT INTO audit_logs(actor_id,action,object_type,object_id,request_id,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        actor,
        action,
        type,
        id,
        requestId,
        stamp(now()));
  }

  private String hash(Object body) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(body)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Object mutate(
      User actor, String action, String key, Object body, Supplier<Object> change) {
    if (key != null && (key.isBlank() || key.length() > 150))
      throw ApiError.bad("幂等键长度应为 1 至 150 字符");
    return tx.execute(
        status -> {
          String digest = hash(body);
          if (key != null) {
            advisory("idempotency:" + actor.id() + ":" + action + ":" + key);
            var results =
                db.queryForList(
                    "SELECT request_hash,result_json FROM idempotency_results WHERE user_id=? AND"
                        + " action=? AND request_key=?",
                    actor.id(),
                    action,
                    key);
            if (!results.isEmpty()) {
              if (!results.getFirst().get("request_hash").equals(digest))
                throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同请求");
              try {
                return json.readValue(
                    results.getFirst().get("result_json").toString(), Object.class);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            }
          }
          Object result = change.get();
          if (key != null && !(result instanceof ApiError)) {
            try {
              db.update(
                  "INSERT INTO"
                      + " idempotency_results(user_id,action,request_key,request_hash,result_json,created_at)"
                      + " VALUES(?,?,?,?,?,?)",
                  actor.id(),
                  action,
                  key,
                  digest,
                  json.writeValueAsString(result),
                  stamp(now()));
            } catch (tools.jackson.core.JacksonException e) {
              throw new IllegalStateException(e);
            }
          }
          return result;
        });
  }

  int maxHours() {
    return db.queryForObject(
        "SELECT max_reservation_hours FROM settings WHERE id=1", Integer.class);
  }

  private List<String> restrictionTypes(long room, OffsetDateTime start, OffsetDateTime end) {
    return db.queryForList(
        "SELECT DISTINCT source_type FROM restrictions WHERE classroom_id=? AND active AND"
            + " start_time<? AND (end_time IS NULL OR end_time>?) ORDER BY source_type",
        String.class,
        room,
        stamp(end),
        stamp(start));
  }

  private boolean occupied(long seat, OffsetDateTime start, OffsetDateTime end) {
    return db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM reservations WHERE seat_id=? AND start_time<? AND end_time>?"
            + " AND (status='CHECKED_IN' OR (status='PENDING' AND check_in_deadline>?)))",
        Boolean.class,
        seat,
        stamp(end),
        stamp(start),
        stamp(now()));
  }

  List<Availability> availability(
      User actor, long roomId, OffsetDateTime start, OffsetDateTime end, Boolean power) {
    Classroom room = room(roomId);
    access(actor, room);
    TimeRules.reservation(start, end, maxHours(), now());
    TimeRules.opening(room, start, end);
    List<String> roomTypes = restrictionTypes(roomId, start, end);
    return db.query(
        "SELECT * FROM seats WHERE classroom_id=? AND (?::boolean IS NULL OR has_power=?) ORDER BY"
            + " id LIMIT 200",
        (rs, n) -> {
          var types = new ArrayList<>(roomTypes);
          boolean used = occupied(rs.getLong("id"), start, end);
          if (used) types.add("M1");
          if (!rs.getBoolean("enabled")) types.add("DISABLED");
          return new Availability(
              rs.getLong("id"),
              roomId,
              rs.getString("label"),
              rs.getBoolean("has_power"),
              types.isEmpty(),
              types);
        },
        roomId,
        power,
        power);
  }

  private String reservationQuery() {
    return "SELECT r.*,s.label seat_label,s.classroom_id,c.name classroom_name FROM reservations r"
        + " JOIN seats s ON s.id=r.seat_id JOIN classrooms c ON c.id=s.classroom_id ";
  }

  private RowMapper<Reservation> reservationMapper() {
    return (rs, n) ->
        new Reservation(
            rs.getLong("id"),
            rs.getLong("seat_id"),
            rs.getString("seat_label"),
            rs.getLong("classroom_id"),
            rs.getString("classroom_name"),
            offset(rs, "start_time"),
            offset(rs, "end_time"),
            rs.getString("status"),
            offset(rs, "check_in_deadline"),
            offset(rs, "checked_in_at"),
            rs.getString("cancel_reason"));
  }

  Reservation reservation(long id) {
    return required(db.query(reservationQuery() + "WHERE r.id=?", reservationMapper(), id));
  }

  private long pageOffset(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 200) throw ApiError.bad("页码应大于等于 1，每页数量应为 1 至 200");
    return ((long) page - 1) * pageSize;
  }

  List<Reservation> mine(User actor) {
    return mine(actor, 1, 200);
  }

  List<Reservation> mine(User actor, int page, int pageSize) {
    return mine(actor, page, pageSize, null);
  }

  List<Reservation> mine(User actor, int page, int pageSize, String statusGroup) {
    long offset = pageOffset(page, pageSize);
    if (statusGroup != null && !List.of("current", "history").contains(statusGroup))
      throw ApiError.bad("预约记录分类应为 current 或 history");
    return db.query(
        reservationQuery()
            + "WHERE r.user_id=? AND (?::text IS NULL OR (?::text='current' AND r.status"
            + " IN('PENDING','CHECKED_IN')) OR (?::text='history' AND r.status NOT"
            + " IN('PENDING','CHECKED_IN'))) ORDER BY r.start_time DESC,r.id DESC LIMIT ? OFFSET ?",
        reservationMapper(),
        actor.id(),
        statusGroup,
        statusGroup,
        statusGroup,
        pageSize,
        offset);
  }

  private void own(User actor, long reservationId) {
    Long user =
        db.queryForObject("SELECT user_id FROM reservations WHERE id=?", Long.class, reservationId);
    if (user == null || user != actor.id()) throw ApiError.forbidden();
  }

  Object reserve(User actor, ReserveRequest input, String key, String requestId) {
    if (!actor.has("STUDENT")) throw ApiError.forbidden();
    return mutate(
        actor,
        "reservation.create",
        key,
        input,
        () -> {
          Seat initial = seat(input.seatId());
          lockRoom(initial.classroomId());
          sweepLocked(initial.classroomId(), requestId);
          db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, actor.id());
          User current = user(actor.id());
          Seat target = seat(input.seatId());
          Classroom room = room(target.classroomId());
          access(current, room);
          TimeRules.reservation(input.startTime(), input.endTime(), maxHours(), now());
          TimeRules.opening(room, input.startTime(), input.endTime());
          if (!target.enabled()) throw ApiError.conflict("座位已停用");
          if (!restrictionTypes(room.id(), input.startTime(), input.endTime()).isEmpty()
              || occupied(target.id(), input.startTime(), input.endTime()))
            throw ApiError.conflict("该座位在所选时段不可预约");
          boolean duplicate =
              db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM reservations WHERE user_id=? AND start_time<? AND"
                      + " end_time>? AND (status='CHECKED_IN' OR (status='PENDING' AND"
                      + " check_in_deadline>?)))",
                  Boolean.class,
                  current.id(),
                  stamp(input.endTime()),
                  stamp(input.startTime()),
                  stamp(now()));
          if (duplicate) throw ApiError.conflict("同一时间只能预约一个座位");
          long id =
              db.queryForObject(
                  "INSERT INTO"
                      + " reservations(user_id,seat_id,start_time,end_time,status,check_in_deadline,created_at)"
                      + " VALUES(?,?,?,?,'PENDING',?,?) RETURNING id",
                  Long.class,
                  current.id(),
                  target.id(),
                  stamp(input.startTime()),
                  stamp(input.endTime()),
                  stamp(input.startTime().plusMinutes(15)),
                  stamp(now()));
          for (Instant slot = input.startTime().toInstant();
              slot.isBefore(input.endTime().toInstant());
              slot = slot.plus(Duration.ofHours(1)))
            db.update(
                "INSERT INTO seat_slots(seat_id,slot_start,reservation_id) VALUES(?,?,?)",
                target.id(),
                stamp(slot),
                id);
          audit(actor.id(), "RESERVATION_CREATED", "reservation", id, requestId);
          return reservation(id);
        });
  }

  Object cancel(User actor, long id, String key, String requestId) {
    return mutate(
        actor,
        "reservation.cancel:" + id,
        key,
        Map.of("id", id),
        () -> {
          Reservation initial = reservation(id);
          lockRoom(initial.classroomId());
          own(actor, id);
          sweepLocked(initial.classroomId(), requestId);
          Reservation current = reservation(id);
          if (!List.of("PENDING", "CHECKED_IN").contains(current.status())
              || !now().isBefore(current.endTime().toInstant()))
            throw ApiError.conflict("当前预约不能取消");
          db.update(
              "UPDATE reservations SET status='CANCELLED',cancel_reason='USER' WHERE id=?", id);
          db.update("DELETE FROM seat_slots WHERE reservation_id=?", id);
          audit(actor.id(), "RESERVATION_CANCELLED", "reservation", id, requestId);
          return reservation(id);
        });
  }

  Object checkIn(User actor, long id, String code, String key, String requestId) {
    if (code == null || !code.matches("[0-9]{6}")) throw ApiError.bad("签到码必须为六位数字");
    Object result =
        mutate(
            actor,
            "reservation.checkin:" + id,
            key,
            Map.of("id", id, "codeHash", hash(code)),
            () -> {
              Reservation initial = reservation(id);
              lockRoom(initial.classroomId());
              own(actor, id);
              sweepLocked(initial.classroomId(), requestId);
              Reservation current = reservation(id);
              if (!current.status().equals("PENDING")) throw ApiError.conflict("当前预约不能签到");
              if (!TimeRules.canCheckIn(current.startTime().toInstant(), now()))
                throw ApiError.conflict("签到时间为预约开始前后各 15 分钟，截止时刻不可签到");
              if (db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM restrictions WHERE classroom_id=? AND"
                      + " source_type='M3' AND active AND start_time<=? AND (end_time IS NULL OR"
                      + " end_time>?))",
                  Boolean.class,
                  current.classroomId(),
                  stamp(now()),
                  stamp(now()))) throw ApiError.conflict("教室维修期间不能签到");
              var attempts =
                  db.queryForList(
                      "SELECT failures,last_attempt FROM check_in_attempts WHERE reservation_id=?",
                      id);
              if (!attempts.isEmpty()
                  && ((Number) attempts.getFirst().get("failures")).intValue() >= 5
                  && ((Timestamp) attempts.getFirst().get("last_attempt"))
                      .toInstant()
                      .plus(Duration.ofMinutes(15))
                      .isAfter(now()))
                throw new ApiError(429, "CHECK_IN_RATE_LIMITED", "连续签到失败，请 15 分钟后重试");
              var codes =
                  db.queryForList(
                      "SELECT code_hash FROM check_in_codes WHERE classroom_id=? AND campus_date=?",
                      String.class,
                      current.classroomId(),
                      now().atZone(TimeRules.CAMPUS).toLocalDate());
              if (codes.isEmpty() || !passwords.matches(code, codes.getFirst())) {
                db.update(
                    "INSERT INTO check_in_attempts(reservation_id,failures,last_attempt)"
                        + " VALUES(?,1,?) ON CONFLICT(reservation_id) DO UPDATE SET failures=CASE"
                        + " WHEN check_in_attempts.last_attempt<? THEN 1 ELSE"
                        + " check_in_attempts.failures+1 END,last_attempt=excluded.last_attempt",
                    id,
                    stamp(now()),
                    stamp(now().minus(Duration.ofMinutes(15))));
                return new ApiError(400, "INVALID_CHECK_IN_CODE", "教室签到码不正确或当天未设置");
              }
              db.update(
                  "UPDATE reservations SET status='CHECKED_IN',checked_in_at=? WHERE id=? AND"
                      + " status='PENDING'",
                  stamp(now()),
                  id);
              db.update("DELETE FROM check_in_attempts WHERE reservation_id=?", id);
              audit(actor.id(), "RESERVATION_CHECKED_IN", "reservation", id, requestId);
              return reservation(id);
            });
    if (result instanceof ApiError error) throw error;
    return result;
  }

  void sweep() {
    var due =
        db.query(
            reservationQuery()
                + "WHERE (r.status='PENDING' AND r.check_in_deadline<=?) OR (r.status='CHECKED_IN'"
                + " AND r.end_time<=?) ORDER BY s.classroom_id,r.id",
            reservationMapper(),
            stamp(now()),
            stamp(now()));
    for (Reservation candidate : due) {
      try {
        tx.executeWithoutResult(
            status -> {
              lockRoom(candidate.classroomId());
              expireLocked(reservation(candidate.id()), "timeout-scan");
            });
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .error(
                "Reservation timeout scan failed for reservation {} in classroom {}",
                candidate.id(),
                candidate.classroomId(),
                e);
      }
    }
  }

  private void sweepLocked(long room, String requestId) {
    var due =
        db.query(
            reservationQuery()
                + "WHERE s.classroom_id=? AND ((r.status='PENDING' AND r.check_in_deadline<=?) OR"
                + " (r.status='CHECKED_IN' AND r.end_time<=?)) ORDER BY r.id",
            reservationMapper(),
            room,
            stamp(now()),
            stamp(now()));
    for (Reservation reservation : due) expireLocked(reservation, requestId);
  }

  private void expireLocked(Reservation reservation, String requestId) {
    if (reservation.status().equals("CHECKED_IN")
        && !reservation.endTime().toInstant().isAfter(now())) {
      db.update(
          "UPDATE reservations SET status='COMPLETED' WHERE id=? AND status='CHECKED_IN'",
          reservation.id());
      db.update("DELETE FROM seat_slots WHERE reservation_id=?", reservation.id());
      audit(null, "RESERVATION_COMPLETED", "reservation", reservation.id(), requestId);
      return;
    }
    if (!reservation.status().equals("PENDING")
        || reservation.checkInDeadline().toInstant().isAfter(now())) return;
    boolean maintenance =
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM restrictions WHERE classroom_id=? AND source_type='M3' AND"
                + " active AND start_time<=? AND (end_time IS NULL OR end_time>?))",
            Boolean.class,
            reservation.classroomId(),
            stamp(reservation.checkInDeadline()),
            stamp(reservation.checkInDeadline()));
    db.update(
        "UPDATE reservations SET status=?,cancel_reason=? WHERE id=? AND status='PENDING'",
        maintenance ? "CANCELLED" : "NO_SHOW",
        maintenance ? "MAINTENANCE" : "NO_SHOW",
        reservation.id());
    db.update("DELETE FROM seat_slots WHERE reservation_id=?", reservation.id());
    if (!maintenance)
      db.update(
          "INSERT INTO violations(user_id,reservation_id,reason,created_at) SELECT"
              + " user_id,id,'NO_SHOW',? FROM reservations WHERE id=? ON"
              + " CONFLICT(reservation_id,reason) DO NOTHING",
          stamp(now()),
          reservation.id());
    audit(
        null,
        maintenance ? "RESERVATION_MAINTENANCE_CANCELLED" : "RESERVATION_NO_SHOW",
        "reservation",
        reservation.id(),
        requestId);
  }

  List<Map<String, Object>> violations(User actor) {
    return violations(actor, 1, 200);
  }

  List<Map<String, Object>> violations(User actor, int page, int pageSize) {
    long pageOffset = pageOffset(page, pageSize);
    return db.query(
        "SELECT id,reservation_id,reason,created_at FROM violations WHERE user_id=? ORDER BY"
            + " created_at DESC,id DESC LIMIT ? OFFSET ?",
        (rs, n) ->
            Map.of(
                "id",
                rs.getLong("id"),
                "reservationId",
                rs.getLong("reservation_id"),
                "reason",
                rs.getString("reason"),
                "createdAt",
                offset(rs, "created_at")),
        actor.id(),
        pageSize,
        pageOffset);
  }

  RestrictionResult restriction(String type, long sourceId, RestrictionInput input) {
    if (!List.of("M2", "M3").contains(type)
        || sourceId <= 0
        || input.version() <= 0
        || input.startTime() == null
        || input.requestId() == null
        || !input.requestId().matches("[A-Za-z0-9._:-]{1,100}")) throw ApiError.bad("限制命令参数不正确");
    User actor = user(input.actorId());
    if (type.equals("M2")) {
      if (input.active()) require(actor, "ADMIN");
      else if (!actor.has("APPLICANT") && !actor.has("ADMIN")) throw ApiError.forbidden();
    } else if (!input.active()) require(actor, "ADMIN");
    else if (!actor.has("TECHNICIAN") && !actor.has("ADMIN")) throw ApiError.forbidden();
    if (type.equals("M2")) TimeRules.interval(input.startTime(), input.endTime());
    else if (input.endTime() != null) throw ApiError.bad("维修限制的结束时间应为空");
    return tx.execute(
        status -> {
          advisory("restriction:" + type + ":" + sourceId);
          lockRoom(input.classroomId());
          String digest =
              hash(
                  Arrays.asList(
                      type,
                      sourceId,
                      input.version(),
                      input.active(),
                      input.classroomId(),
                      input.startTime().toInstant(),
                      input.endTime() == null ? null : input.endTime().toInstant(),
                      input.actorId()));
          var existing =
              db.queryForList(
                  "SELECT version,active,classroom_id,command_hash,start_time FROM restrictions"
                      + " WHERE source_type=? AND source_id=?",
                  type,
                  sourceId);
          if (!existing.isEmpty()) {
            var row = existing.getFirst();
            long version = ((Number) row.get("version")).longValue();
            if (((Number) row.get("classroom_id")).longValue() != input.classroomId())
              throw ApiError.conflict("限制来源所属教室不能变更");
            if (input.version() < version) {
              sweepLocked(input.classroomId(), input.requestId());
              return new RestrictionResult(version, (Boolean) row.get("active"), false);
            }
            if (input.version() == version) {
              if (!row.get("command_hash").equals(digest))
                throw new ApiError(409, "VERSION_CONFLICT", "同一版本参数不一致");
              sweepLocked(input.classroomId(), input.requestId());
              return new RestrictionResult(version, (Boolean) row.get("active"), false);
            }
          }
          if (!type.equals("M3") || !input.active())
            sweepLocked(input.classroomId(), input.requestId());
          Classroom classroom = room(input.classroomId());
          if (input.active() && type.equals("M2")) {
            access(actor, classroom);
            TimeRules.opening(classroom, input.startTime(), input.endTime());
            if (input.endTime().toInstant().isBefore(now())) throw ApiError.conflict("申请时段已经结束");
            boolean reservationConflict =
                db.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM reservations r JOIN seats s ON s.id=r.seat_id"
                        + " WHERE s.classroom_id=? AND r.start_time<? AND r.end_time>? AND r.status"
                        + " IN('PENDING','CHECKED_IN'))",
                    Boolean.class,
                    classroom.id(),
                    stamp(input.endTime()),
                    stamp(input.startTime()));
            boolean restrictionConflict =
                db.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM restrictions WHERE classroom_id=? AND active AND"
                        + " NOT(source_type=? AND source_id=?) AND start_time<? AND (end_time IS"
                        + " NULL OR end_time>?))",
                    Boolean.class,
                    classroom.id(),
                    type,
                    sourceId,
                    stamp(input.endTime()),
                    stamp(input.startTime()));
            if (reservationConflict || restrictionConflict)
              throw ApiError.conflict("申请时段存在预约或教室限制");
          }
          Instant start =
              type.equals("M3") && input.active() && existing.isEmpty()
                  ? now()
                  : input.startTime().toInstant();
          if (type.equals("M3") && !existing.isEmpty())
            start = ((Timestamp) existing.getFirst().get("start_time")).toInstant();
          db.update(
              "INSERT INTO"
                  + " restrictions(source_type,source_id,version,active,classroom_id,start_time,end_time,command_hash,actor_id,request_id,updated_at,released_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(source_type,source_id) DO UPDATE"
                  + " SET version=excluded.version,active=excluded.active,start_time=excluded.start_time,end_time=excluded.end_time,command_hash=excluded.command_hash,actor_id=excluded.actor_id,request_id=excluded.request_id,updated_at=excluded.updated_at,released_at=excluded.released_at",
              type,
              sourceId,
              input.version(),
              input.active(),
              input.classroomId(),
              stamp(start),
              stamp(input.endTime()),
              digest,
              actor.id(),
              input.requestId(),
              stamp(now()),
              input.active() ? null : stamp(now()));
          if (type.equals("M3") && input.active())
            sweepLocked(input.classroomId(), input.requestId());
          audit(
              actor.id(),
              input.active() ? "RESTRICTION_ESTABLISHED" : "RESTRICTION_RELEASED",
              type,
              sourceId,
              input.requestId());
          return new RestrictionResult(input.version(), input.active(), true);
        });
  }

  Map<String, Integer> settings(User actor) {
    require(actor, "ADMIN");
    return Map.of("maxReservationHours", maxHours());
  }

  Object updateSettings(User actor, int hours, String key, String requestId) {
    require(actor, "ADMIN");
    if (hours < 1 || hours > 12) throw ApiError.bad("时长上限应为 1 至 12 小时");
    return mutate(
        actor,
        "settings.update",
        key,
        Map.of("maxReservationHours", hours),
        () -> {
          db.update("UPDATE settings SET max_reservation_hours=? WHERE id=1", hours);
          audit(actor.id(), "SETTINGS_UPDATED", "settings", 1L, requestId);
          return settings(actor);
        });
  }

  private void validate(ClassroomInput input) {
    if (input.name() == null
        || input.name().isBlank()
        || input.name().length() > 120
        || input.building() == null
        || input.building().isBlank()
        || input.building().length() > 120
        || input.floor() < -10
        || input.floor() > 100
        || (input.department() != null && input.department().length() > 80)
        || input.openTime() == null
        || input.closeTime() == null
        || !input.closeTime().isAfter(input.openTime())) throw ApiError.bad("教室名称、位置及开放时间不正确");
  }

  private void validate(SeatInput input) {
    if (input.label() == null || input.label().isBlank() || input.label().length() > 40)
      throw ApiError.bad("座位编号长度应为 1 至 40 字符");
  }

  Object createRoom(User actor, ClassroomInput input, String key, String requestId) {
    require(actor, "ADMIN");
    validate(input);
    return mutate(
        actor,
        "classroom.create",
        key,
        input,
        () -> {
          long id =
              db.queryForObject(
                  "INSERT INTO"
                      + " classrooms(name,building,floor,department,open_time,close_time,enabled)"
                      + " VALUES(?,?,?,?,?,?,?) RETURNING id",
                  Long.class,
                  input.name(),
                  input.building(),
                  input.floor(),
                  input.department(),
                  input.openTime(),
                  input.closeTime(),
                  input.enabled());
          audit(actor.id(), "CLASSROOM_CREATED", "classroom", id, requestId);
          return room(id);
        });
  }

  private boolean liveRecords(long room) {
    return db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM reservations r JOIN seats s ON s.id=r.seat_id WHERE"
            + " s.classroom_id=? AND r.status IN('PENDING','CHECKED_IN') AND r.end_time>?) OR"
            + " EXISTS(SELECT 1 FROM restrictions WHERE classroom_id=? AND active AND (end_time IS"
            + " NULL OR end_time>?))",
        Boolean.class,
        room,
        stamp(now()),
        room,
        stamp(now()));
  }

  Object updateRoom(User actor, long id, ClassroomInput input, String key, String requestId) {
    require(actor, "ADMIN");
    validate(input);
    return mutate(
        actor,
        "classroom.update:" + id,
        key,
        input,
        () -> {
          lockRoom(id);
          sweepLocked(id, requestId);
          Classroom previous = room(id);
          if (liveRecords(id)
              && (!input.enabled()
                  || !Objects.equals(previous.department(), input.department())
                  || !previous.openTime().equals(input.openTime())
                  || !previous.closeTime().equals(input.closeTime())))
            throw ApiError.conflict("教室有有效预约或限制，不能更改使用规则");
          db.update(
              "UPDATE classrooms SET"
                  + " name=?,building=?,floor=?,department=?,open_time=?,close_time=?,enabled=?"
                  + " WHERE id=?",
              input.name(),
              input.building(),
              input.floor(),
              input.department(),
              input.openTime(),
              input.closeTime(),
              input.enabled(),
              id);
          audit(actor.id(), "CLASSROOM_UPDATED", "classroom", id, requestId);
          return room(id);
        });
  }

  Object createSeat(User actor, long room, SeatInput input, String key, String requestId) {
    require(actor, "ADMIN");
    validate(input);
    return mutate(
        actor,
        "seat.create:" + room,
        key,
        input,
        () -> {
          lockRoom(room);
          long id =
              db.queryForObject(
                  "INSERT INTO seats(classroom_id,label,has_power,enabled) VALUES(?,?,?,?)"
                      + " RETURNING id",
                  Long.class,
                  room,
                  input.label(),
                  input.hasPower(),
                  input.enabled());
          audit(actor.id(), "SEAT_CREATED", "seat", id, requestId);
          return seat(id);
        });
  }

  Object updateSeat(User actor, long id, SeatInput input, String key, String requestId) {
    require(actor, "ADMIN");
    validate(input);
    return mutate(
        actor,
        "seat.update:" + id,
        key,
        input,
        () -> {
          Seat previous = seat(id);
          lockRoom(previous.classroomId());
          sweepLocked(previous.classroomId(), requestId);
          if (!input.enabled()
              && db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM reservations WHERE seat_id=? AND status"
                      + " IN('PENDING','CHECKED_IN') AND end_time>?)",
                  Boolean.class,
                  id,
                  stamp(now()))) throw ApiError.conflict("座位有有效预约，不能停用");
          db.update(
              "UPDATE seats SET label=?,has_power=?,enabled=? WHERE id=?",
              input.label(),
              input.hasPower(),
              input.enabled(),
              id);
          audit(actor.id(), "SEAT_UPDATED", "seat", id, requestId);
          return seat(id);
        });
  }

  List<Map<String, Object>> codes(User actor) {
    require(actor, "ADMIN");
    return db.query(
        "SELECT c.classroom_id,c.campus_date,r.name FROM check_in_codes c JOIN classrooms r ON"
            + " r.id=c.classroom_id WHERE c.campus_date=? ORDER BY c.classroom_id",
        (rs, n) ->
            Map.of(
                "classroomId",
                rs.getLong("classroom_id"),
                "classroomName",
                rs.getString("name"),
                "date",
                rs.getObject("campus_date", LocalDate.class)),
        now().atZone(TimeRules.CAMPUS).toLocalDate());
  }

  Object updateCode(User actor, long room, String code, String key, String requestId) {
    require(actor, "ADMIN");
    if (code == null || !code.matches("[0-9]{6}")) throw ApiError.bad("签到码必须为六位数字");
    return mutate(
        actor,
        "checkin-code.update:" + room,
        key,
        Map.of("codeHash", hash(code)),
        () -> {
          lockRoom(room);
          LocalDate today = now().atZone(TimeRules.CAMPUS).toLocalDate();
          db.update(
              "INSERT INTO check_in_codes(classroom_id,campus_date,code_hash) VALUES(?,?,?) ON"
                  + " CONFLICT(classroom_id,campus_date) DO UPDATE SET"
                  + " code_hash=excluded.code_hash",
              room,
              today,
              passwords.encode(code));
          audit(actor.id(), "CHECK_IN_CODE_UPDATED", "classroom", room, requestId);
          return Map.of("classroomId", room, "date", today);
        });
  }

  List<Map<String, Object>> logs(User actor) {
    return logs(actor, 1, 200);
  }

  List<Map<String, Object>> logs(User actor, int page, int pageSize) {
    require(actor, "ADMIN");
    long pageOffset = pageOffset(page, pageSize);
    return db.query(
        "SELECT a.*,u.display_name FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id ORDER BY"
            + " a.id DESC LIMIT ? OFFSET ?",
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", rs.getLong("id"));
          row.put("actorId", rs.getObject("actor_id"));
          row.put("actorName", rs.getString("display_name"));
          row.put("action", rs.getString("action"));
          row.put("objectType", rs.getString("object_type"));
          row.put("objectId", rs.getObject("object_id"));
          row.put("requestId", rs.getString("request_id"));
          row.put("createdAt", offset(rs, "created_at"));
          return row;
        },
        pageSize,
        pageOffset);
  }
}
