package cn.campus.resource;

import static cn.campus.resource.AccountModels.*;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
class AccountService {
  private static final Set<String> ROLES = Set.of("STUDENT", "APPLICANT", "TECHNICIAN", "ADMIN");
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Sessions sessions;
  private final ObjectMapper json;
  private final Clock clock;
  private final byte[] hmacKey;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

  AccountService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      Sessions sessions,
      ObjectMapper json,
      Clock clock,
      @Value("${campus.module-key}") String moduleKey) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.sessions = sessions;
    this.json = json;
    this.clock = clock;
    this.hmacKey = moduleKey.getBytes(StandardCharsets.UTF_8);
  }

  private String username(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{2,31}"))
      throw ApiError.bad("用户名应为3至32位，以英文字母开头，只能包含字母、数字和下划线");
    return value.toLowerCase(Locale.ROOT);
  }

  private String name(String value) {
    if (value == null || value.trim().isEmpty() || value.trim().length() > 120)
      throw ApiError.bad("姓名应为1至120字符");
    return value.trim();
  }

  private String department(String value) {
    if (value == null || value.trim().isEmpty()) return null;
    if (value.trim().length() > 80) throw ApiError.bad("院系不能超过80字符");
    return value.trim();
  }

  private void password(String value) {
    if (value == null
        || value.codePointCount(0, value.length()) < 8
        || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw ApiError.bad("密码至少8个字符，UTF-8编码不能超过72字节");
  }

  private List<String> roles(List<String> values) {
    if (values == null
        || values.isEmpty()
        || values.stream().anyMatch(v -> v == null || !ROLES.contains(v)))
      throw ApiError.bad("请选择有效角色");
    return values.stream().distinct().sorted().toList();
  }

  private boolean enabled(Boolean value) {
    if (value == null) throw ApiError.bad("请选择账号是否启用");
    return value;
  }

  private void lock() {
    db.execute(
        (ConnectionCallback<Void>)
            connection -> {
              try (var statement =
                  connection.prepareStatement(
                      "SELECT pg_advisory_xact_lock(hashtextextended('account-management',0))")) {
                statement.execute();
              }
              return null;
            });
  }

  private void admin(long actor) {
    if (!Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users u JOIN user_roles r ON r.user_id=u.id"
                + " WHERE u.id=? AND u.enabled AND r.role='ADMIN')",
            Boolean.class,
            actor))) throw ApiError.forbidden();
  }

  private Account account(long id) {
    var records =
        db.query(
            "SELECT id,username,display_name,department,enabled FROM users WHERE id=?",
            (rs, row) ->
                new Account(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    rs.getString("department"),
                    db.queryForList(
                        "SELECT role FROM user_roles WHERE user_id=? ORDER BY role",
                        String.class,
                        id),
                    rs.getBoolean("enabled")),
            id);
    if (records.isEmpty()) throw ApiError.missing();
    return records.getFirst();
  }

  List<Account> list(long actor, int page, int pageSize, String query) {
    admin(actor);
    if (page < 1 || page > 1_000_000 || pageSize < 1 || pageSize > 100)
      throw ApiError.bad("分页参数不正确");
    String search = query == null ? "" : query.trim();
    if (search.length() > 120) throw ApiError.bad("搜索内容不能超过120字符");
    return db
        .queryForList(
            "SELECT id FROM users WHERE strpos(lower(username),lower(?))>0"
                + " OR strpos(lower(display_name),lower(?))>0 ORDER BY id LIMIT ? OFFSET ?",
            Long.class,
            search,
            search,
            pageSize,
            ((long) page - 1) * pageSize)
        .stream()
        .map(this::account)
        .toList();
  }

  private void key(String value) {
    if (value != null && (value.isBlank() || value.length() > 150))
      throw ApiError.bad("幂等键长度应为1至150字符");
  }

  private String digest(Object request) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsBytes(request)));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private <T> T replay(long owner, String action, String key, String digest, Class<T> type) {
    if (key == null) return null;
    var records =
        db.queryForList(
            "SELECT request_hash,result_json FROM idempotency_results"
                + " WHERE user_id=? AND action=? AND request_key=?",
            owner,
            action,
            key);
    if (records.isEmpty()) return null;
    if (!digest.equals(records.getFirst().get("request_hash")))
      throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同请求");
    return json.readValue(records.getFirst().get("result_json").toString(), type);
  }

  private void remember(long owner, String action, String key, String digest, Object result) {
    if (key != null)
      db.update(
          "INSERT INTO"
              + " idempotency_results(user_id,action,request_key,request_hash,result_json,created_at)"
              + " VALUES(?,?,?,?,?,?)",
          owner,
          action,
          key,
          digest,
          json.writeValueAsString(result),
          Timestamp.from(clock.instant()));
  }

  private void audit(long actor, String action, long target, String requestId) {
    db.update(
        "INSERT INTO audit_logs(actor_id,action,object_type,object_id,request_id,created_at)"
            + " VALUES(?,?,'ACCOUNT',?,?,?)",
        actor,
        action,
        target,
        requestId,
        Timestamp.from(clock.instant()));
  }

  private Account insert(
      String username,
      String password,
      String displayName,
      String department,
      List<String> roles,
      boolean enabled) {
    if (!db.queryForList("SELECT id FROM users WHERE lower(username)=?", Long.class, username)
        .isEmpty()) throw ApiError.conflict("用户名已存在");
    long id =
        db.queryForObject(
            "INSERT INTO users(username,password_hash,display_name,department,enabled)"
                + " VALUES(?,?,?,?,?) RETURNING id",
            Long.class,
            username,
            passwords.encode(password),
            displayName,
            department,
            enabled);
    for (String role : roles)
      db.update("INSERT INTO user_roles(user_id,role) VALUES(?,?)", id, role);
    return account(id);
  }

  Models.User register(Registration input, String key, String requestId) {
    String username = username(input.username());
    String name = name(input.displayName());
    String department = department(input.department());
    password(input.password());
    key(key);
    if (!Set.of("CS", "ART").contains(department == null ? "" : department))
      throw ApiError.bad("请选择院系");
    if (input.roles() != null && !roles(input.roles()).equals(List.of("STUDENT")))
      throw ApiError.bad("注册账号只能具有学生角色");
    return tx.execute(
        status -> {
          lock();
          String digest = digest(input);
          var existing =
              db.queryForList("SELECT id FROM users WHERE lower(username)=?", Long.class, username);
          if (!existing.isEmpty()) {
            Models.User repeated =
                replay(existing.getFirst(), "account.register", key, digest, Models.User.class);
            if (repeated != null) return repeated;
            throw ApiError.conflict("用户名已存在");
          }
          Account result =
              insert(username, input.password(), name, department, List.of("STUDENT"), true);
          Models.User user =
              new Models.User(
                  result.id(),
                  result.username(),
                  result.displayName(),
                  result.department(),
                  result.roles());
          audit(result.id(), "account.register", result.id(), requestId);
          remember(result.id(), "account.register", key, digest, user);
          return user;
        });
  }

  Account create(long actor, CreateAccount input, String key, String requestId) {
    String username = username(input.username());
    String name = name(input.displayName());
    String department = department(input.department());
    List<String> roles = roles(input.roles());
    boolean enabled = enabled(input.enabled());
    password(input.password());
    key(key);
    return tx.execute(
        status -> {
          lock();
          admin(actor);
          String digest = digest(input);
          Account repeated = replay(actor, "account.create", key, digest, Account.class);
          if (repeated != null) return repeated;
          Account result = insert(username, input.password(), name, department, roles, enabled);
          audit(actor, "account.create", result.id(), requestId);
          remember(actor, "account.create", key, digest, result);
          return result;
        });
  }

  private record Change<T>(T value, String invalidate) {}

  private <T> T committed(long id, Supplier<Change<T>> work) {
    String username = account(id).username();
    return sessions.withAccountLock(
        username,
        () -> {
          Change<T> result = tx.execute(status -> work.get());
          if (result.invalidate() != null) sessions.invalidate(result.invalidate());
          return result.value();
        });
  }

  Account update(long actor, long id, UpdateAccount input, String key, String requestId) {
    String name = name(input.displayName());
    String department = department(input.department());
    List<String> roles = roles(input.roles());
    boolean enabled = enabled(input.enabled());
    key(key);
    return committed(
        id,
        () -> {
          lock();
          admin(actor);
          String action = "account.update:" + id;
          String digest = digest(input);
          Account repeated = replay(actor, action, key, digest, Account.class);
          if (repeated != null) return new Change<>(repeated, null);
          Account previous = account(id);
          boolean removesAdmin = !enabled || !roles.contains("ADMIN");
          if (actor == id && removesAdmin) throw ApiError.conflict("不能停用自己或移除自己的管理员角色");
          if (previous.enabled()
              && previous.roles().contains("ADMIN")
              && removesAdmin
              && db.queryForObject(
                      "SELECT count(*) FROM users u JOIN user_roles r ON r.user_id=u.id"
                          + " WHERE u.enabled AND r.role='ADMIN'",
                      Long.class)
                  <= 1) throw ApiError.conflict("必须保留至少一个启用的管理员");
          db.update(
              "UPDATE users SET display_name=?,department=?,enabled=? WHERE id=?",
              name,
              department,
              enabled,
              id);
          db.update("DELETE FROM user_roles WHERE user_id=?", id);
          for (String role : roles)
            db.update("INSERT INTO user_roles(user_id,role) VALUES(?,?)", id, role);
          Account result = account(id);
          audit(actor, "account.update", id, requestId);
          remember(actor, action, key, digest, result);
          boolean invalidates =
              result.enabled() != previous.enabled()
                  || !Objects.equals(result.department(), previous.department())
                  || !result.roles().equals(previous.roles());
          return new Change<>(result, invalidates ? previous.username() : null);
        });
  }

  Map<String, Boolean> resetPassword(
      long actor, long id, PasswordChange input, String key, String requestId) {
    password(input.password());
    key(key);
    return committed(
        id,
        () -> {
          lock();
          admin(actor);
          String action = "account.password:" + id;
          String digest = digest(input);
          if (replay(actor, action, key, digest, Object.class) != null)
            return new Change<>(Map.of("success", true), null);
          Account target = account(id);
          db.update(
              "UPDATE users SET password_hash=? WHERE id=?",
              passwords.encode(input.password()),
              id);
          Map<String, Boolean> result = Map.of("success", true);
          audit(actor, "account.password", id, requestId);
          remember(actor, action, key, digest, result);
          return new Change<>(result, target.username());
        });
  }
}
