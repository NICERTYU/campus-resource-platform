package cn.campus.resource;

import static cn.campus.resource.AccountModels.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountIntegrationTest {
  static final String MODULE_KEY = "account-integration-module-key-32-characters";
  static final AtomicReference<CountDownLatch> LOGIN_ENTERED = new AtomicReference<>();
  static final AtomicReference<CountDownLatch> LOGIN_CONTINUE = new AtomicReference<>();
  final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");
  final ObjectMapper json = new ObjectMapper();
  ConfigurableApplicationContext app;
  JdbcTemplate db;
  AccountService accounts;
  ResourceService resources;
  String base;

  @TestConfiguration(proxyBeanMethods = false)
  static class ControlledPasswordEncoder {
    @Bean
    @Primary
    PasswordEncoder controlledEncoder() {
      return new PasswordEncoder() {
        final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

        public String encode(CharSequence raw) {
          return delegate.encode(raw);
        }

        public boolean matches(CharSequence raw, String encoded) {
          CountDownLatch entered = LOGIN_ENTERED.get();
          CountDownLatch proceed = LOGIN_CONTINUE.get();
          if (entered != null && proceed != null) {
            entered.countDown();
            try {
              if (!proceed.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Login race timed out");
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(error);
            }
          }
          return delegate.matches(raw, encoded);
        }
      };
    }
  }

  @BeforeAll
  void start() {
    postgres.start();
    app =
        new SpringApplication(ResourceApplication.class, ControlledPasswordEncoder.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--campus.module-key=" + MODULE_KEY,
                "--campus.timeout-scan-ms=3600000");
    db = app.getBean(JdbcTemplate.class);
    accounts = app.getBean(AccountService.class);
    resources = app.getBean(ResourceService.class);
    base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (app != null) app.close();
    postgres.stop();
  }

  @BeforeEach
  void seed() {
    LOGIN_ENTERED.set(null);
    LOGIN_CONTINUE.set(null);
    db.execute(
        "TRUNCATE"
            + " audit_logs,idempotency_results,check_in_attempts,check_in_codes,violations,restrictions,seat_slots,reservations,seats,classrooms,user_roles,users"
            + " RESTART IDENTITY CASCADE");
    app.getBean(DemoSeeder.class).seed();
  }

  class Client {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
    String csrf;

    Client() throws Exception {
      refresh();
    }

    void refresh() throws Exception {
      csrf =
          json.readTree(send("GET", "/api/v1/auth/csrf", null, false, null).body())
              .get("token")
              .asText();
    }

    HttpResponse<String> send(String method, String path, Object body, boolean withCsrf, String key)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .header("Content-Type", "application/json");
      if (withCsrf) request.header("X-CSRF-TOKEN", csrf);
      if (key != null) request.header("Idempotency-Key", key);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> login(String username, String password) throws Exception {
      var result =
          send(
              "POST",
              "/api/v1/auth/login",
              Map.of("username", username, "password", password),
              true,
              null);
      refresh();
      return result;
    }

    String cookie() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals("JSESSIONID"))
          .map(c -> "JSESSIONID=" + c.getValue())
          .findFirst()
          .orElseThrow();
    }
  }

  Client login(String username) throws Exception {
    var client = new Client();
    expect(200, client.login(username, "Campus123!"));
    return client;
  }

  JsonNode expect(int status, HttpResponse<String> response) throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  Map<String, Object> registration(String username) {
    return new LinkedHashMap<>(
        Map.of(
            "username",
            username,
            "password",
            "NewPassword123!",
            "displayName",
            "  新同学  ",
            "department",
            "CS"));
  }

  Map<String, Object> create(String username, List<String> roles, boolean enabled) {
    var input = registration(username);
    input.put("roles", roles);
    input.put("enabled", enabled);
    return input;
  }

  Map<String, Object> update(String name, String department, List<String> roles, boolean enabled) {
    return Map.of(
        "displayName", name, "department", department, "roles", roles, "enabled", enabled);
  }

  HttpResponse<String> identity(String cookie) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + "/internal/v1/identity"))
            .header("Content-Type", "application/json")
            .header("X-Module-Key", MODULE_KEY)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("cookie", cookie, "write", false))))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void registrationRequiresCsrfAndCreatesOnlyStudentWithoutLoggingIn() throws Exception {
    var client = new Client();
    expect(
        403, client.send("POST", "/api/v1/auth/register", registration("NewStudent"), false, null));
    var result =
        expect(
            201,
            client.send("POST", "/api/v1/auth/register", registration("NewStudent"), true, null));
    assertEquals("newstudent", result.get("username").asText());
    assertEquals("新同学", result.get("displayName").asText());
    assertEquals(List.of("STUDENT"), json.convertValue(result.get("roles"), List.class));
    assertFalse(result.has("password"));
    assertFalse(result.has("passwordHash"));
    expect(401, client.send("GET", "/api/v1/auth/me", null, false, null));
    expect(200, client.login("NEWSTUDENT", "NewPassword123!"));
    expect(200, client.send("GET", "/api/v1/classrooms", null, false, null));
    String hash =
        db.queryForObject(
            "SELECT password_hash FROM users WHERE username='newstudent'", String.class);
    assertTrue(new BCryptPasswordEncoder().matches("NewPassword123!", hash));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action='account.register'", Integer.class));
  }

  @Test
  void registrationRejectsPrivilegeEscalationAndInvalidInputs() throws Exception {
    var client = new Client();
    var invalid = new ArrayList<Map<String, Object>>();
    for (var entry :
        List.of(
            Map.entry("username", "ab"),
            Map.entry("username", "1student"),
            Map.entry("username", "中文名"),
            Map.entry("username", "a".repeat(33)),
            Map.entry("username", "student-name"),
            Map.entry("password", "short"),
            Map.entry("password", "密".repeat(25)),
            Map.entry("displayName", " "),
            Map.entry("displayName", "名".repeat(121)),
            Map.entry("department", "MATH"),
            Map.entry("department", ""))) {
      var input = registration("valid_student");
      input.put(entry.getKey(), entry.getValue());
      invalid.add(input);
    }
    for (var roles :
        List.of(List.of("ADMIN"), List.of("STUDENT", "APPLICANT"), List.of("UNKNOWN"), List.of())) {
      var input = registration("valid_student");
      input.put("roles", roles);
      invalid.add(input);
    }
    for (var input : invalid)
      expect(400, client.send("POST", "/api/v1/auth/register", input, true, null));
    var nullRole = registration("valid_student");
    nullRole.put("roles", Arrays.asList((String) null));
    expect(400, client.send("POST", "/api/v1/auth/register", nullRole, true, null));
    assertEquals(6, db.queryForObject("SELECT count(*) FROM users", Integer.class));
    var boundary = registration("password_boundary");
    boundary.put("password", "密".repeat(24));
    expect(201, client.send("POST", "/api/v1/auth/register", boundary, true, null));
    var spaced = registration("password_spaces");
    spaced.put("password", " 12345678 ");
    expect(201, client.send("POST", "/api/v1/auth/register", spaced, true, null));
    expect(401, client.login("password_spaces", "12345678"));
    expect(200, client.login("password_spaces", " 12345678 "));
  }

  @Test
  void simultaneousCaseVariantsCreateExactlyOneAccountAndReturnConflicts() throws Exception {
    var first = new Client();
    var second = new Client();
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results =
          pool.invokeAll(
              List.<Callable<Integer>>of(
                  () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return first
                        .send(
                            "POST", "/api/v1/auth/register", registration("Concurrent"), true, null)
                        .statusCode();
                  },
                  () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return second
                        .send(
                            "POST", "/api/v1/auth/register", registration("CONCURRENT"), true, null)
                        .statusCode();
                  }));
      assertEquals(Set.of(201, 409), Set.of(results.get(0).get(), results.get(1).get()));
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM users WHERE lower(username)='concurrent'", Integer.class));
    expect(409, first.send("POST", "/api/v1/auth/register", registration("STUDENT"), true, null));
  }

  @Test
  void registrationIdempotencyHasKeyedPasswordDigestAndNoCredentialResults() throws Exception {
    var client = new Client();
    var body = registration("idem_student");
    var first =
        expect(201, client.send("POST", "/api/v1/auth/register", body, true, "registration-key"));
    assertEquals(
        first,
        expect(201, client.send("POST", "/api/v1/auth/register", body, true, "registration-key")));
    expect(409, client.send("POST", "/api/v1/auth/register", body, true, "other-key"));
    body.put("password", "DifferentPassword123!");
    expect(409, client.send("POST", "/api/v1/auth/register", body, true, "registration-key"));
    body.put("password", "NewPassword123!");
    var row =
        db.queryForMap(
            "SELECT request_hash,result_json FROM idempotency_results WHERE"
                + " action='account.register'");
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(MODULE_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    var typed = new Registration("idem_student", "NewPassword123!", "  新同学  ", "CS", null);
    String expected =
        HexFormat.of()
            .formatHex(
                mac.doFinal(
                    app.getBean(tools.jackson.databind.ObjectMapper.class)
                        .writeValueAsBytes(typed)));
    assertEquals(expected, row.get("request_hash"));
    assertFalse(row.get("result_json").toString().contains("Password"));
    assertFalse(row.get("result_json").toString().contains("password"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action='account.register'", Integer.class));
    expect(
        400,
        client.send("POST", "/api/v1/auth/register", registration("another_student"), true, " "));
  }

  @Test
  void managementRequiresAuthenticatedAdminAndCsrfOnEveryWrite() throws Exception {
    var anonymous = new Client();
    var student = login("student");
    var admin = login("admin");
    long target = resources.byUsername("student2").id();
    for (var client : List.of(anonymous, student)) {
      int status = client == anonymous ? 401 : 403;
      expect(status, client.send("GET", "/api/v1/admin/accounts", null, false, null));
      expect(
          status,
          client.send(
              "POST",
              "/api/v1/admin/accounts",
              create("managed", List.of("ADMIN"), true),
              true,
              null));
      expect(
          status,
          client.send(
              "PUT",
              "/api/v1/admin/accounts/" + target,
              update("Changed", "CS", List.of("ADMIN"), true),
              true,
              null));
      expect(
          status,
          client.send(
              "POST",
              "/api/v1/admin/accounts/" + target + "/password",
              Map.of("password", "ChangedPassword123!"),
              true,
              null));
    }
    expect(
        403,
        admin.send(
            "POST",
            "/api/v1/admin/accounts",
            create("managed", List.of("STUDENT"), true),
            false,
            null));
    expect(
        403,
        admin.send(
            "PUT",
            "/api/v1/admin/accounts/" + target,
            update("Changed", "CS", List.of("STUDENT"), true),
            false,
            null));
    expect(
        403,
        admin.send(
            "POST",
            "/api/v1/admin/accounts/" + target + "/password",
            Map.of("password", "ChangedPassword123!"),
            false,
            null));
    assertEquals(6, db.queryForObject("SELECT count(*) FROM users", Integer.class));
  }

  @Test
  void adminCreatesEditsAndListsDisabledAccountsWithSearchBeforePagination() throws Exception {
    var admin = login("admin");
    var body = create("ManagedOne", List.of("STUDENT", "APPLICANT", "APPLICANT"), false);
    body.put("department", null);
    var created =
        expect(201, admin.send("POST", "/api/v1/admin/accounts", body, true, "create-user"));
    long id = created.get("id").asLong();
    assertFalse(created.get("enabled").asBoolean());
    assertTrue(created.get("department").isNull());
    assertEquals(
        List.of("APPLICANT", "STUDENT"), json.convertValue(created.get("roles"), List.class));
    assertEquals(
        created,
        expect(201, admin.send("POST", "/api/v1/admin/accounts", body, true, "create-user")));
    body.put("password", "DifferentPassword123!");
    expect(409, admin.send("POST", "/api/v1/admin/accounts", body, true, "create-user"));
    expect(409, admin.send("POST", "/api/v1/admin/accounts", body, true, null));
    expect(401, new Client().login("managedone", "NewPassword123!"));
    var updatedBody = update("受理人员", "ART", List.of("TECHNICIAN"), true);
    var updated =
        expect(
            200, admin.send("PUT", "/api/v1/admin/accounts/" + id, updatedBody, true, "edit-user"));
    assertEquals("managedone", updated.get("username").asText());
    assertEquals(
        updated,
        expect(
            200,
            admin.send("PUT", "/api/v1/admin/accounts/" + id, updatedBody, true, "edit-user")));
    var filtered =
        expect(
            200,
            admin.send(
                "GET", "/api/v1/admin/accounts?query=MANAGED&pageSize=1", null, false, null));
    assertEquals(id, filtered.get(0).get("id").asLong());
    var byName =
        expect(
            200,
            admin.send(
                "GET",
                "/api/v1/admin/accounts?query=" + URLEncoder.encode("受理", StandardCharsets.UTF_8),
                null,
                false,
                null));
    assertEquals(1, byName.size());
    assertEquals(
        0,
        expect(200, admin.send("GET", "/api/v1/admin/accounts?query=%25", null, false, null))
            .size());
    var one =
        expect(
            200, admin.send("GET", "/api/v1/admin/accounts?pageSize=1&page=1", null, false, null));
    var two =
        expect(
            200, admin.send("GET", "/api/v1/admin/accounts?pageSize=1&page=2", null, false, null));
    assertNotEquals(one.get(0).get("id"), two.get(0).get("id"));
    for (String query : List.of("page=0", "pageSize=0", "pageSize=101", "page=1000001"))
      expect(400, admin.send("GET", "/api/v1/admin/accounts?" + query, null, false, null));
    expect(404, admin.send("PUT", "/api/v1/admin/accounts/99999", updatedBody, true, null));
    expect(
        404,
        admin.send(
            "POST",
            "/api/v1/admin/accounts/99999/password",
            Map.of("password", "ChangedPassword123!"),
            true,
            null));
  }

  @Test
  void adminInputValidationRejectsUnknownEmptyOrNullRolesAndMissingFlags() throws Exception {
    var admin = login("admin");
    for (var roles :
        Arrays.asList(List.of("UNKNOWN"), List.of(), Arrays.asList((String) null), null)) {
      var body = create("invalid_user", List.of("STUDENT"), true);
      body.put("roles", roles);
      expect(400, admin.send("POST", "/api/v1/admin/accounts", body, true, null));
    }
    var missing = create("invalid_user", List.of("STUDENT"), true);
    missing.remove("enabled");
    expect(400, admin.send("POST", "/api/v1/admin/accounts", missing, true, null));
    var longDepartment = create("invalid_user", List.of("STUDENT"), true);
    longDepartment.put("department", "a".repeat(81));
    expect(400, admin.send("POST", "/api/v1/admin/accounts", longDepartment, true, null));
    expect(
        400,
        admin.send(
            "POST", "/api/v1/admin/accounts/1/password", Map.of("password", "short"), true, null));
  }

  @Test
  void profileRoleAndDisableChangesRevokeAllResourceAndInternalSessions() throws Exception {
    var admin = login("admin");
    long id = resources.byUsername("student").id();
    var modifications =
        List.of(
            update("学生", "ART", List.of("STUDENT"), true),
            update("学生", "ART", List.of("APPLICANT"), true),
            update("学生", "ART", List.of("APPLICANT"), false));
    for (var change : modifications) {
      var first = login("student");
      var second = login("student");
      String cookie = first.cookie();
      expect(200, identity(cookie));
      expect(200, admin.send("PUT", "/api/v1/admin/accounts/" + id, change, true, null));
      expect(401, first.send("GET", "/api/v1/auth/me", null, false, null));
      expect(401, second.send("GET", "/api/v1/classrooms", null, false, null));
      expect(401, identity(cookie));
    }
    expect(401, new Client().login("student", "Campus123!"));
    var disabled =
        expect(200, admin.send("GET", "/api/v1/admin/accounts?query=student", null, false, null));
    assertTrue(json.convertValue(disabled, List.class).size() >= 1);
    assertFalse(disabled.get(0).get("enabled").asBoolean());
    expect(
        200,
        admin.send(
            "PUT",
            "/api/v1/admin/accounts/" + id,
            update("学生", "CS", List.of("STUDENT"), true),
            true,
            null));
    expect(200, new Client().login("STUDENT", "Campus123!"));
  }

  @Test
  void passwordResetRevokesSessionsPreservesNewLoginOnReplayAndNeverStoresPlaintext()
      throws Exception {
    var admin = login("admin");
    var first = login("student");
    var second = login("student");
    String cookie = first.cookie();
    long id = resources.byUsername("student").id();
    var password = Map.of("password", "ResetPassword123!");
    expect(
        200,
        admin.send(
            "POST", "/api/v1/admin/accounts/" + id + "/password", password, true, "reset-key"));
    expect(401, first.send("GET", "/api/v1/auth/me", null, false, null));
    expect(401, second.send("GET", "/api/v1/classrooms", null, false, null));
    expect(401, identity(cookie));
    expect(401, new Client().login("student", "Campus123!"));
    var fresh = new Client();
    expect(200, fresh.login("student", "ResetPassword123!"));
    expect(
        200,
        admin.send(
            "POST", "/api/v1/admin/accounts/" + id + "/password", password, true, "reset-key"));
    expect(200, fresh.send("GET", "/api/v1/auth/me", null, false, null));
    expect(
        409,
        admin.send(
            "POST",
            "/api/v1/admin/accounts/" + id + "/password",
            Map.of("password", "AnotherPassword123!"),
            true,
            "reset-key"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action='account.password'", Integer.class));
    String stored =
        db.queryForObject(
            "SELECT result_json FROM idempotency_results WHERE action=?",
            String.class,
            "account.password:" + id);
    assertEquals(json.readTree("{\"success\":true}"), json.readTree(stored));
    assertFalse(db.queryForList("SELECT * FROM audit_logs").toString().contains("ResetPassword"));
    assertTrue(
        new BCryptPasswordEncoder()
            .matches(
                "ResetPassword123!",
                db.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id)));
  }

  @Test
  void administratorsCannotRemoveThemselvesAndConcurrentMutualDisableLeavesAnAdmin()
      throws Exception {
    long first = resources.byUsername("admin").id();
    var admin = login("admin");
    expect(
        409,
        admin.send(
            "PUT",
            "/api/v1/admin/accounts/" + first,
            update("管理员", "CS", List.of("ADMIN"), false),
            true,
            null));
    expect(
        409,
        admin.send(
            "PUT",
            "/api/v1/admin/accounts/" + first,
            update("管理员", "CS", List.of("STUDENT"), true),
            true,
            null));
    var added =
        expect(
            201,
            admin.send(
                "POST",
                "/api/v1/admin/accounts",
                create("second_admin", List.of("ADMIN"), true),
                true,
                null));
    long second = added.get("id").asLong();
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results =
          pool.invokeAll(
              List.<Callable<Integer>>of(
                  () -> attemptDisable(barrier, first, second),
                  () -> attemptDisable(barrier, second, first)));
      assertEquals(Set.of(200, 403), Set.of(results.get(0).get(), results.get(1).get()));
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM users u JOIN user_roles r ON u.id=r.user_id WHERE u.enabled AND"
                + " r.role='ADMIN'",
            Integer.class));
  }

  int attemptDisable(CyclicBarrier barrier, long actor, long target) throws Exception {
    barrier.await(10, TimeUnit.SECONDS);
    try {
      accounts.update(
          actor,
          target,
          new UpdateAccount("管理员", "CS", List.of("ADMIN"), false),
          null,
          "admin-race");
      return 200;
    } catch (ApiError error) {
      return error.status;
    }
  }

  @Test
  void failedTransactionDoesNotInvalidateSessionOrPartiallyChangePassword() throws Exception {
    var admin = login("admin");
    var student = login("student");
    long id = resources.byUsername("student").id();
    db.execute(
        "ALTER TABLE audit_logs ADD CONSTRAINT reject_password_update"
            + " CHECK(action<>'account.password')");
    try {
      expect(
          409,
          admin.send(
              "POST",
              "/api/v1/admin/accounts/" + id + "/password",
              Map.of("password", "RollbackPassword123!"),
              true,
              "rollback"));
      expect(200, student.send("GET", "/api/v1/auth/me", null, false, null));
      expect(200, new Client().login("student", "Campus123!"));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM idempotency_results", Integer.class));
    } finally {
      db.execute("ALTER TABLE audit_logs DROP CONSTRAINT reject_password_update");
    }
  }

  @Test
  void changingOnlyOwnDisplayNamePreservesCurrentSession() throws Exception {
    var admin = login("admin");
    var user = resources.byUsername("admin");
    String cookie = admin.cookie();
    expect(
        200,
        admin.send(
            "PUT",
            "/api/v1/admin/accounts/" + user.id(),
            update("新的管理员姓名", user.department(), user.roles(), true),
            true,
            "rename-self"));
    var me = expect(200, admin.send("GET", "/api/v1/auth/me", null, false, null));
    assertEquals("新的管理员姓名", me.get("displayName").asText());
    expect(200, identity(cookie));
  }

  @Test
  void delayedOldPasswordLoginCannotEscapePasswordResetSessionRevocation() throws Exception {
    var admin = login("admin");
    var student = new Client();
    long id = resources.byUsername("student").id();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    LOGIN_ENTERED.set(entered);
    LOGIN_CONTINUE.set(proceed);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var login = pool.submit(() -> student.login("STUDENT", "Campus123!"));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var reset =
          pool.submit(
              () ->
                  admin.send(
                      "POST",
                      "/api/v1/admin/accounts/" + id + "/password",
                      Map.of("password", "ConcurrentPassword123!"),
                      true,
                      null));
      try {
        assertThrows(
            TimeoutException.class,
            () -> reset.get(200, TimeUnit.MILLISECONDS),
            "Password change must wait until the already-authenticating session is registered");
      } finally {
        proceed.countDown();
      }
      expect(200, login.get(10, TimeUnit.SECONDS));
      expect(200, reset.get(10, TimeUnit.SECONDS));
      expect(401, student.send("GET", "/api/v1/auth/me", null, false, null));
      expect(401, new Client().login("student", "Campus123!"));
    } finally {
      proceed.countDown();
      LOGIN_ENTERED.set(null);
      LOGIN_CONTINUE.set(null);
    }
  }

  @Test
  void lateCreatedTechnicianRemainsDiscoverableAfterMoreThanTwoHundredStudents() throws Exception {
    db.update(
        "INSERT INTO users(username,password_hash,display_name,department)"
            + " SELECT 'bulk_'||n,'unused','批量学生'||n,'CS' FROM generate_series(1,205) n");
    db.update(
        "INSERT INTO user_roles(user_id,role) SELECT id,'STUDENT' FROM users WHERE username LIKE"
            + " 'bulk_%'");
    var admin = login("admin");
    var created =
        expect(
            201,
            admin.send(
                "POST",
                "/api/v1/admin/accounts",
                create("late_technician", List.of("TECHNICIAN"), true),
                true,
                null));
    long id = created.get("id").asLong();
    assertTrue(id > 200);
    expect(
        201,
        admin.send(
            "POST",
            "/api/v1/admin/accounts",
            create("disabled_technician", List.of("TECHNICIAN"), false),
            true,
            null));
    var general = expect(200, admin.send("GET", "/api/v1/admin/users", null, false, null));
    assertEquals(200, general.size());
    assertTrue(
        json.convertValue(general, List.class).stream()
            .noneMatch(value -> ((Map<?, ?>) value).get("username").equals("late_technician")));
    var management =
        expect(200, admin.send("GET", "/api/v1/admin/users?role=TECHNICIAN", null, false, null));
    var internalRequest =
        HttpRequest.newBuilder(URI.create(base + "/internal/v1/users?role=TECHNICIAN"))
            .header("X-Module-Key", MODULE_KEY)
            .GET()
            .build();
    var internal =
        expect(
            200,
            HttpClient.newHttpClient().send(internalRequest, HttpResponse.BodyHandlers.ofString()));
    assertEquals(management, internal);
    assertEquals(2, management.size());
    assertEquals(id, management.get(1).get("id").asLong());
    for (JsonNode user : management) {
      assertTrue(json.convertValue(user.get("roles"), List.class).contains("TECHNICIAN"));
      assertEquals(
          Set.of("id", "username", "displayName", "department", "roles"),
          json.convertValue(user, Map.class).keySet());
    }
    expect(400, admin.send("GET", "/api/v1/admin/users?role=UNKNOWN", null, false, null));
    var invalid =
        HttpRequest.newBuilder(URI.create(base + "/internal/v1/users?role=UNKNOWN"))
            .header("X-Module-Key", MODULE_KEY)
            .GET()
            .build();
    expect(400, HttpClient.newHttpClient().send(invalid, HttpResponse.BodyHandlers.ofString()));
    expect(
        403,
        login("student").send("GET", "/api/v1/admin/users?role=TECHNICIAN", null, false, null));
    var unauthorized =
        HttpRequest.newBuilder(URI.create(base + "/internal/v1/users?role=TECHNICIAN"))
            .header("X-Module-Key", "invalid")
            .GET()
            .build();
    expect(
        403, HttpClient.newHttpClient().send(unauthorized, HttpResponse.BodyHandlers.ofString()));
  }
}
