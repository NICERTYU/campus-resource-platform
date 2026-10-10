package cn.campus.workflow;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkflowIntegrationTest {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
  static final ObjectMapper JSON = new ObjectMapper();
  static final String MODULE_KEY = "workflow-test-module-secret-32-characters";
  static final AtomicInteger REMOTE_STATUS = new AtomicInteger(200);
  static final AtomicInteger COMMANDS = new AtomicInteger();
  static final AtomicInteger LOSE_RESPONSES = new AtomicInteger();
  static final AtomicReference<String> LAST_IDENTITY_COOKIE = new AtomicReference<>();
  static final AtomicReference<JsonNode> LAST_IDENTITY_PAYLOAD = new AtomicReference<>();
  static final Map<String, JsonNode> RESTRICTIONS = new ConcurrentHashMap<>();
  static final HttpServer RESOURCE = startResource();
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired OutboxProcessor processor;
  @Autowired WorkflowRepository repository;
  @Autowired ResourceClient resourceClient;
  @Autowired TransactionTemplate transactions;
  private final HttpClient browser = HttpClient.newHttpClient();

  @AfterAll
  static void closeResource() {
    RESOURCE.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "workflow.resource-url", () -> "http://localhost:" + RESOURCE.getAddress().getPort());
    registry.add("workflow.module-key", () -> MODULE_KEY);
    registry.add("workflow.retry-delay-ms", () -> "3600000");
  }

  @BeforeEach
  void clean() {
    db.execute(
        "TRUNCATE idempotency_results,outbox,audit_logs,classroom_applications,repair_orders"
            + " RESTART IDENTITY");
    REMOTE_STATUS.set(200);
    COMMANDS.set(0);
    LOSE_RESPONSES.set(0);
    RESTRICTIONS.clear();
    LAST_IDENTITY_COOKIE.set(null);
    LAST_IDENTITY_PAYLOAD.set(null);
  }

  @Test
  void identityForwardsHttpCookieAndWriteCsrfContextTogether() {
    var user =
        resourceClient.identity(
            "JSESSIONID=student", "valid-csrf", true, "identity-forwarding-test");
    assertEquals(1, user.id());
    assertEquals("JSESSIONID=student", LAST_IDENTITY_COOKIE.get());
    assertEquals("JSESSIONID=student", LAST_IDENTITY_PAYLOAD.get().path("cookie").asText());
    assertEquals("valid-csrf", LAST_IDENTITY_PAYLOAD.get().path("csrfToken").asText());
    assertTrue(LAST_IDENTITY_PAYLOAD.get().path("write").asBoolean());
    ApiException rejected =
        assertThrows(
            ApiException.class,
            () ->
                resourceClient.identity(
                    "JSESSIONID=student", "invalid-csrf", true, "identity-forgery-test"));
    assertEquals(403, rejected.status);
  }

  @Test
  void identityAndCsrfAreVerifiedForEveryWriteAndRecordsAreScoped() throws Exception {
    assertEquals(401, call("GET", "/repair-orders", null, null, null, false).statusCode());
    assertEquals(
        403,
        call(
                "POST",
                "/repair-orders",
                Map.of("classroomId", 1, "description", "灯坏了"),
                "student",
                null,
                false)
            .statusCode());
    assertEquals(
        403,
        call("POST", "/classroom-applications", application(1), "student", null, true)
            .statusCode());
    assertEquals(
        403,
        call(
                "POST",
                "/repair-orders",
                Map.of("classroomId", 2, "description", "灯坏了"),
                "student",
                null,
                true)
            .statusCode());
    var reported =
        call(
            "POST",
            "/repair-orders",
            Map.of("classroomId", 1, "description", "灯坏了"),
            "student",
            null,
            true);
    assertEquals(201, reported.statusCode());
    assertEquals(1, parse(call("GET", "/repair-orders", null, "student", null, false)).size());
    assertEquals(0, parse(call("GET", "/repair-orders", null, "other", null, false)).size());
    assertEquals(1, parse(call("GET", "/repair-orders", null, "admin", null, false)).size());
    assertEquals(0, COMMANDS.get());
  }

  @Test
  void listPaginationIsStableAndFiltersVisibilityBeforeSelectingPages() throws Exception {
    long firstApplication =
        parse(call("POST", "/classroom-applications", application(1), "applicant", null, true))
            .path("id")
            .asLong();
    long secondApplication =
        parse(call("POST", "/classroom-applications", application(1), "applicant", null, true))
            .path("id")
            .asLong();
    long privateApplication =
        parse(call("POST", "/classroom-applications", application(1), "admin", null, true))
            .path("id")
            .asLong();
    assertEquals(
        secondApplication,
        parse(
                call(
                    "GET",
                    "/classroom-applications?page=1&pageSize=1",
                    null,
                    "applicant",
                    null,
                    false))
            .get(0)
            .path("id")
            .asLong());
    assertEquals(
        firstApplication,
        parse(
                call(
                    "GET",
                    "/classroom-applications?page=2&pageSize=1",
                    null,
                    "applicant",
                    null,
                    false))
            .get(0)
            .path("id")
            .asLong());
    assertEquals(
        0,
        parse(
                call(
                    "GET",
                    "/classroom-applications?page=3&pageSize=1",
                    null,
                    "applicant",
                    null,
                    false))
            .size());
    assertEquals(
        privateApplication,
        parse(call("GET", "/classroom-applications?pageSize=1", null, "admin", null, false))
            .get(0)
            .path("id")
            .asLong());
    assertEquals(
        0,
        parse(call("GET", "/classroom-applications?pageSize=1", null, "other", null, false))
            .size());

    long firstRepair =
        parse(
                call(
                    "POST",
                    "/repair-orders",
                    Map.of("classroomId", 1, "description", "first repair"),
                    "student",
                    null,
                    true))
            .path("id")
            .asLong();
    long secondRepair =
        parse(
                call(
                    "POST",
                    "/repair-orders",
                    Map.of("classroomId", 1, "description", "second repair"),
                    "student",
                    null,
                    true))
            .path("id")
            .asLong();
    long privateRepair =
        parse(
                call(
                    "POST",
                    "/repair-orders",
                    Map.of("classroomId", 1, "description", "other reporter"),
                    "other",
                    null,
                    true))
            .path("id")
            .asLong();
    assertEquals(
        secondRepair,
        parse(call("GET", "/repair-orders?page=1&pageSize=1", null, "student", null, false))
            .get(0)
            .path("id")
            .asLong());
    assertEquals(
        firstRepair,
        parse(call("GET", "/repair-orders?page=2&pageSize=1", null, "student", null, false))
            .get(0)
            .path("id")
            .asLong());
    assertEquals(
        0,
        parse(call("GET", "/repair-orders?page=3&pageSize=1", null, "student", null, false))
            .size());
    call(
        "POST",
        "/repair-orders/" + firstRepair + "/assign",
        Map.of("technicianId", 4),
        "admin",
        null,
        true);
    call(
        "POST",
        "/repair-orders/" + privateRepair + "/assign",
        Map.of("technicianId", 4),
        "admin",
        null,
        true);
    assertEquals(
        privateRepair,
        parse(call("GET", "/repair-orders?page=1&pageSize=1", null, "tech", null, false))
            .get(0)
            .path("id")
            .asLong());
    assertEquals(
        firstRepair,
        parse(call("GET", "/repair-orders?page=2&pageSize=1", null, "tech", null, false))
            .get(0)
            .path("id")
            .asLong());
    assertEquals(
        privateRepair,
        parse(call("GET", "/repair-orders?pageSize=1", null, "admin", null, false))
            .get(0)
            .path("id")
            .asLong());
    for (String endpoint : java.util.List.of("/classroom-applications", "/repair-orders")) {
      for (String query :
          java.util.List.of(
              "?page=0", "?page=-1", "?pageSize=0", "?pageSize=201", "?page=invalid")) {
        var rejected = call("GET", endpoint + query, null, "admin", null, false);
        assertEquals(400, rejected.statusCode());
        assertTrue(parse(rejected).has("requestId"));
      }
      assertEquals(
          0,
          parse(call("GET", endpoint + "?page=2147483647&pageSize=200", null, "admin", null, false))
              .size());
    }
  }

  @Test
  void applicationApprovalAndWithdrawalUseVersionedNetworkRestrictions() throws Exception {
    var created =
        call("POST", "/classroom-applications", application(1), "applicant", "create-key", true);
    assertEquals(201, created.statusCode());
    long id = parse(created).path("id").asLong();
    assertEquals(
        created.body(),
        call("POST", "/classroom-applications", application(1), "applicant", "create-key", true)
            .body());
    var changed = application(1);
    changed.put("purpose", "Different");
    assertEquals(
        409,
        call("POST", "/classroom-applications", changed, "applicant", "create-key", true)
            .statusCode());
    assertEquals(
        403,
        call(
                "POST",
                "/classroom-applications/" + id + "/approve",
                Map.of(),
                "applicant",
                null,
                true)
            .statusCode());
    var approved =
        call(
            "POST",
            "/classroom-applications/" + id + "/approve",
            Map.of("comment", "同意"),
            "admin",
            "approve",
            true);
    assertEquals(200, approved.statusCode());
    assertEquals("APPROVED", parse(approved).path("status").asText());
    assertTrue(RESTRICTIONS.get("M2/" + id).path("active").asBoolean());
    assertEquals(
        approved.body(),
        call(
                "POST",
                "/classroom-applications/" + id + "/approve",
                Map.of("comment", "同意"),
                "admin",
                "approve",
                true)
            .body());
    assertEquals(1, COMMANDS.get());
    assertEquals(
        403,
        call("POST", "/classroom-applications/" + id + "/withdraw", Map.of(), "other", null, true)
            .statusCode());
    var withdrawn =
        call(
            "POST",
            "/classroom-applications/" + id + "/withdraw",
            Map.of(),
            "applicant",
            null,
            true);
    assertEquals("WITHDRAWN", parse(withdrawn).path("status").asText());
    assertFalse(RESTRICTIONS.get("M2/" + id).path("active").asBoolean());
    assertEquals(2, RESTRICTIONS.get("M2/" + id).path("version").asInt());
    assertEquals(3, db.queryForObject("SELECT count(*) FROM audit_logs", Integer.class));
  }

  @Test
  void repairOnlyAssignedTechnicianCanStartAndAcceptanceReleasesOnlyItsSource() throws Exception {
    long id =
        parse(
                call(
                    "POST",
                    "/repair-orders",
                    Map.of("classroomId", 1, "description", "空调故障"),
                    "student",
                    null,
                    true))
            .path("id")
            .asLong();
    assertEquals(
        409,
        call("POST", "/repair-orders/" + id + "/accept", Map.of(), "admin", null, true)
            .statusCode());
    assertEquals(
        400,
        call(
                "POST",
                "/repair-orders/" + id + "/assign",
                Map.of("technicianId", 1),
                "admin",
                null,
                true)
            .statusCode());
    assertEquals(
        "ASSIGNED",
        parse(
                call(
                    "POST",
                    "/repair-orders/" + id + "/assign",
                    Map.of("technicianId", 4),
                    "admin",
                    null,
                    true))
            .path("status")
            .asText());
    assertEquals(
        403,
        call("POST", "/repair-orders/" + id + "/start", Map.of(), "othertech", null, true)
            .statusCode());
    assertEquals(
        "IN_PROGRESS",
        parse(call("POST", "/repair-orders/" + id + "/start", Map.of(), "tech", null, true))
            .path("status")
            .asText());
    assertEquals(
        "AWAITING_ACCEPTANCE",
        parse(
                call(
                    "POST",
                    "/repair-orders/" + id + "/complete",
                    Map.of("note", "已修复"),
                    "tech",
                    null,
                    true))
            .path("status")
            .asText());
    assertTrue(RESTRICTIONS.get("M3/" + id).path("active").asBoolean());
    assertEquals(
        "IN_PROGRESS",
        parse(
                call(
                    "POST",
                    "/repair-orders/" + id + "/return",
                    Map.of("comment", "还漏水"),
                    "admin",
                    null,
                    true))
            .path("status")
            .asText());
    assertEquals(1, COMMANDS.get());
    call("POST", "/repair-orders/" + id + "/complete", Map.of("note", "再次修复"), "tech", null, true);
    assertEquals(
        "ACCEPTED",
        parse(call("POST", "/repair-orders/" + id + "/accept", Map.of(), "admin", null, true))
            .path("status")
            .asText());
    assertFalse(RESTRICTIONS.get("M3/" + id).path("active").asBoolean());
    assertEquals(2, RESTRICTIONS.get("M3/" + id).path("version").asInt());
  }

  @Test
  void remoteFailureAndLostResponseRemainProcessingAndRecoverFromPersistentOutbox()
      throws Exception {
    long id =
        parse(call("POST", "/classroom-applications", application(1), "applicant", null, true))
            .path("id")
            .asLong();
    REMOTE_STATUS.set(503);
    var processing =
        call(
            "POST",
            "/classroom-applications/" + id + "/approve",
            Map.of(),
            "admin",
            "approval-failure",
            true);
    assertEquals(202, processing.statusCode());
    assertEquals("PROCESSING", parse(processing).path("status").asText());
    assertEquals("APPROVE", parse(processing).path("pendingAction").asText());
    assertFalse(parse(processing).path("lastError").isNull());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM audit_logs", Integer.class));
    assertEquals(
        409,
        call(
                "POST",
                "/classroom-applications/" + id + "/withdraw",
                Map.of(),
                "applicant",
                null,
                true)
            .statusCode());
    REMOTE_STATUS.set(200);
    LOSE_RESPONSES.set(3);
    processor.process(db.queryForObject("SELECT id FROM outbox", Long.class));
    assertTrue(RESTRICTIONS.get("M2/" + id).path("active").asBoolean());
    assertEquals(
        "PROCESSING",
        db.queryForObject(
            "SELECT status FROM classroom_applications WHERE id=?", String.class, id));
    LOSE_RESPONSES.set(0);
    // A fresh processor uses only the persisted intent, as after application restart.
    OutboxProcessor restarted =
        new OutboxProcessor(repository, transactions, resourceClient, java.time.Clock.systemUTC());
    db.update(
        "UPDATE outbox SET status='SENDING',lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second'");
    restarted.retry();
    assertEquals(
        "APPROVED",
        db.queryForObject(
            "SELECT status FROM classroom_applications WHERE id=?", String.class, id));
    assertEquals(1, RESTRICTIONS.get("M2/" + id).path("version").asInt());
    assertEquals(2, db.queryForObject("SELECT count(*) FROM audit_logs", Integer.class));
    assertEquals(
        processing.body(),
        call(
                "POST",
                "/classroom-applications/" + id + "/approve",
                Map.of(),
                "admin",
                "approval-failure",
                true)
            .body());
  }

  @Test
  void deterministicConflictRestoresBusinessStateAndNextAttemptUsesHigherVersion()
      throws Exception {
    long id =
        parse(call("POST", "/classroom-applications", application(1), "applicant", null, true))
            .path("id")
            .asLong();
    REMOTE_STATUS.set(409);
    var failure =
        call(
            "POST",
            "/classroom-applications/" + id + "/approve",
            Map.of(),
            "admin",
            "conflict",
            true);
    assertEquals(409, failure.statusCode());
    assertTrue(parse(failure).has("requestId"));
    assertEquals(
        "PENDING",
        db.queryForObject(
            "SELECT status FROM classroom_applications WHERE id=?", String.class, id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT source_version FROM classroom_applications WHERE id=?", Integer.class, id));
    REMOTE_STATUS.set(200);
    assertEquals(
        failure.body(),
        call(
                "POST",
                "/classroom-applications/" + id + "/approve",
                Map.of(),
                "admin",
                "conflict",
                true)
            .body());
    assertEquals(
        "APPROVED",
        parse(
                call(
                    "POST",
                    "/classroom-applications/" + id + "/approve",
                    Map.of(),
                    "admin",
                    "corrected",
                    true))
            .path("status")
            .asText());
    assertEquals(2, RESTRICTIONS.get("M2/" + id).path("version").asInt());
  }

  @Test
  void concurrentIdenticalCreationProducesOneRecordAndOneAuditEntry() throws Exception {
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<HttpResponse<String>> create =
          () -> {
            barrier.await();
            return call(
                "POST",
                "/classroom-applications",
                application(1),
                "applicant",
                "concurrent-key",
                true);
          };
      var first = pool.submit(create);
      var second = pool.submit(create);
      var a = first.get();
      var b = second.get();
      assertEquals(201, a.statusCode());
      assertEquals(201, b.statusCode());
      assertEquals(a.body(), b.body());
      assertEquals(
          1, db.queryForObject("SELECT count(*) FROM classroom_applications", Integer.class));
      assertEquals(1, db.queryForObject("SELECT count(*) FROM audit_logs", Integer.class));
    }
  }

  @Test
  void concurrentApprovalsCannotCreateTwoVersionsOrDuplicateAudit() throws Exception {
    long id =
        parse(call("POST", "/classroom-applications", application(1), "applicant", null, true))
            .path("id")
            .asLong();
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<HttpResponse<String>> approve =
          () -> {
            barrier.await();
            return call(
                "POST",
                "/classroom-applications/" + id + "/approve",
                Map.of(),
                "admin",
                null,
                true);
          };
      var first = pool.submit(approve);
      var second = pool.submit(approve);
      var statuses = java.util.List.of(first.get().statusCode(), second.get().statusCode());
      assertTrue(statuses.contains(200));
      assertTrue(statuses.contains(409));
      assertEquals(1, COMMANDS.get());
      assertEquals(
          "APPROVED",
          db.queryForObject(
              "SELECT status FROM classroom_applications WHERE id=?", String.class, id));
      assertEquals(2, db.queryForObject("SELECT count(*) FROM audit_logs", Integer.class));
    }
  }

  @Test
  void failureInLocalAuditRollsBackCreationAndIdempotencyTogether() throws Exception {
    db.execute(
        "CREATE FUNCTION reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'injected audit failure'; END $$");
    db.execute(
        "CREATE TRIGGER fail_audit BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION"
            + " reject_audit()");
    try {
      assertEquals(
          500,
          call(
                  "POST",
                  "/repair-orders",
                  Map.of("classroomId", 1, "description", "灯坏了"),
                  "student",
                  "rollback",
                  true)
              .statusCode());
      assertEquals(0, db.queryForObject("SELECT count(*) FROM repair_orders", Integer.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM idempotency_results", Integer.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM audit_logs", Integer.class));
    } finally {
      db.execute("DROP TRIGGER fail_audit ON audit_logs");
      db.execute("DROP FUNCTION reject_audit()");
    }
    assertEquals(
        201,
        call(
                "POST",
                "/repair-orders",
                Map.of("classroomId", 1, "description", "灯坏了"),
                "student",
                "rollback",
                true)
            .statusCode());
  }

  @Test
  void schemaUpgradeRetainsExistingBusinessDataAndAddsTechnicianVisibilityIndex() {
    var initial =
        org.flywaydb.core.Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("workflow_upgrade")
            .defaultSchema("workflow_upgrade")
            .locations("classpath:db/migration")
            .target("1")
            .load();
    initial.migrate();
    db.update(
        "INSERT INTO"
            + " workflow_upgrade.repair_orders(classroom_id,classroom_name,reporter_id,reporter_name,description,status)"
            + " VALUES(1,'A101',1,'student','existing repair','REPORTED')");
    var upgrade =
        org.flywaydb.core.Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("workflow_upgrade")
            .defaultSchema("workflow_upgrade")
            .locations("classpath:db/migration")
            .load();
    upgrade.migrate();
    assertEquals(
        "existing repair",
        db.queryForObject("SELECT description FROM workflow_upgrade.repair_orders", String.class));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname='workflow_upgrade' AND"
                + " indexname='repair_technician_idx'",
            Integer.class));
    assertEquals(0, upgrade.migrate().migrationsExecuted);
    db.execute("DROP SCHEMA workflow_upgrade CASCADE");
  }

  private HttpResponse<String> call(
      String method, String path, Object body, String user, String key, boolean csrf)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Content-Type", "application/json");
    if (user != null) request.header("Cookie", "JSESSIONID=" + user);
    if (csrf) request.header("X-CSRF-TOKEN", "valid-csrf");
    if (key != null) request.header("Idempotency-Key", key);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode parse(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  private static Map<String, Object> application(long classroom) {
    String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString();
    return new HashMap<>(
        Map.of(
            "classroomId",
            classroom,
            "startTime",
            date + "T09:15:00+08:00",
            "endTime",
            date + "T10:30:00+08:00",
            "purpose",
            "社团活动"));
  }

  private static HttpServer startResource() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext("/internal/v1/", WorkflowIntegrationTest::resource);
      server.start();
      return server;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void resource(HttpExchange exchange) throws IOException {
    try {
      if (!MODULE_KEY.equals(exchange.getRequestHeaders().getFirst("X-Module-Key"))) {
        respond(exchange, 403, Map.of("code", "FORBIDDEN"));
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.endsWith("/identity")) {
        JsonNode body =
            JSON.readTree(
                new String(
                    exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        LAST_IDENTITY_COOKIE.set(exchange.getRequestHeaders().getFirst("Cookie"));
        LAST_IDENTITY_PAYLOAD.set(body);
        if (!body.path("cookie").asText().isBlank()
            && !body.path("cookie").asText().equals(LAST_IDENTITY_COOKIE.get())) {
          respond(
              exchange,
              400,
              Map.of("code", "COOKIE_NOT_FORWARDED", "message", "Session cookie missing"));
          return;
        }
        String username = body.path("cookie").asText().replace("JSESSIONID=", "");
        var user = users().get(username);
        if (user == null) {
          respond(exchange, 401, Map.of("code", "UNAUTHENTICATED", "message", "请登录"));
          return;
        }
        if (body.path("write").asBoolean()
            && !body.path("csrfToken").asText().equals("valid-csrf")) {
          respond(exchange, 403, Map.of("code", "CSRF_INVALID", "message", "CSRF错误"));
          return;
        }
        respond(exchange, 200, user);
      } else if (path.contains("/classrooms/")) {
        long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
        respond(
            exchange,
            200,
            Map.of(
                "id",
                id,
                "name",
                "A101",
                "building",
                "A",
                "floor",
                1,
                "department",
                id == 2 ? "Math" : "",
                "openTime",
                "08:00",
                "closeTime",
                "22:00",
                "enabled",
                true,
                "seatCount",
                20));
      } else if (path.endsWith("/users")) respond(exchange, 200, users().values());
      else if (path.contains("/restrictions/")) {
        COMMANDS.incrementAndGet();
        JsonNode command =
            JSON.readTree(
                new String(
                    exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        if (REMOTE_STATUS.get() != 200) {
          respond(
              exchange,
              REMOTE_STATUS.get(),
              Map.of("code", "RESOURCE_CONFLICT", "message", "资源时段冲突"));
          return;
        }
        RESTRICTIONS.put(path.substring(path.indexOf("/restrictions/") + 14), command);
        if (LOSE_RESPONSES.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
          exchange.close();
          return;
        }
        respond(
            exchange,
            200,
            Map.of(
                "version",
                command.path("version").asLong(),
                "active",
                command.path("active").asBoolean(),
                "applied",
                true));
      } else respond(exchange, 404, Map.of("code", "NOT_FOUND"));
    } catch (RuntimeException error) {
      respond(exchange, 500, Map.of("code", "MOCK_ERROR", "message", error.toString()));
    }
  }

  private static Map<String, Map<String, Object>> users() {
    return Map.of(
        "student",
        user(1, "student", "STUDENT"),
        "applicant",
        user(2, "applicant", "APPLICANT"),
        "admin",
        user(3, "admin", "ADMIN"),
        "tech",
        user(4, "tech", "TECHNICIAN"),
        "other",
        user(5, "other", "STUDENT"),
        "othertech",
        user(6, "othertech", "TECHNICIAN"));
  }

  private static Map<String, Object> user(long id, String username, String role) {
    return Map.of(
        "id",
        id,
        "username",
        username,
        "displayName",
        username,
        "department",
        "CS",
        "roles",
        java.util.List.of(role));
  }

  private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] payload =
        JSON.writeValueAsString(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }
}
