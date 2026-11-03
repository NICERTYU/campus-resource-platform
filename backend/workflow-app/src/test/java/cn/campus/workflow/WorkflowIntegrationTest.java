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
  static final AtomicReference<String> LAST_USERS_QUERY = new AtomicReference<>();
  static final Map<String, JsonNode> RESTRICTIONS = new ConcurrentHashMap<>();
  static final HttpServer RESOURCE = startResource();
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired OutboxProcessor processor;
  @Autowired WorkflowRepository repository;
  @Autowired ResourceClient resourceClient;
  @Autowired TransactionTemplate transactions;
  @Autowired RepairEscalationService escalation;
  @Autowired RepairTimeoutScanner repairScanner;
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
    registry.add("workflow.repair.scan-ms", () -> "3600000");
    registry.add("workflow.approval.steps", () -> "1");
    registry.add("workflow.approval.expiry-scan-ms", () -> "3600000");
  }

  @BeforeEach
  void clean() {
    db.execute(
        "TRUNCATE"
            + " repair_escalations,repair_history,application_history,idempotency_results,outbox,audit_logs,classroom_applications,repair_orders"
            + " RESTART IDENTITY");
    REMOTE_STATUS.set(200);
    COMMANDS.set(0);
    LOSE_RESPONSES.set(0);
    RESTRICTIONS.clear();
    LAST_IDENTITY_COOKIE.set(null);
    LAST_IDENTITY_PAYLOAD.set(null);
    LAST_USERS_QUERY.set(null);
  }

  @Test
  void technicianLookupFiltersAtResourceServiceBeforeAnyGeneralUserLimit() {
    var technicians = resourceClient.users("technician-filter");
    assertEquals("role=TECHNICIAN", LAST_USERS_QUERY.get());
    assertEquals(2, technicians.size());
    assertTrue(technicians.stream().allMatch(user -> user.roles().contains("TECHNICIAN")));
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

  long reportedRepair() throws Exception {
    return parse(
            call(
                "POST",
                "/repair-orders",
                Map.of("classroomId", 1, "description", "M3专项故障"),
                "student",
                null,
                true))
        .path("id")
        .asLong();
  }

  @Test
  void m3NewRepairsPersistConfiguredDeadlineAndIdempotentCreationKeepsIt() throws Exception {
    var body = Map.of("classroomId", 1, "description", "期限验证");
    var response = call("POST", "/repair-orders", body, "student", "deadline-create", true);
    assertEquals(201, response.statusCode());
    var record = parse(response);
    var created = java.time.OffsetDateTime.parse(record.path("createdAt").asText());
    var deadline = java.time.OffsetDateTime.parse(record.path("repairDeadline").asText());
    assertEquals(java.time.Duration.ofHours(24), java.time.Duration.between(created, deadline));
    assertEquals(
        response.body(),
        call("POST", "/repair-orders", body, "student", "deadline-create", true).body());
    assertEquals(
        deadline.toInstant(),
        db.queryForObject(
                "SELECT repair_deadline FROM repair_orders WHERE id=?",
                java.time.OffsetDateTime.class,
                record.path("id").asLong())
            .toInstant());
  }

  @Test
  void m3TimeoutBoundaryIsIdempotentAndKeepsRepairAndRestrictionVersions() throws Exception {
    long id = reportedRepair();
    assignRepair(id);
    call("POST", "/repair-orders/" + id + "/start", Map.of(), "tech", null, true);
    var deadline = java.time.OffsetDateTime.parse("2026-11-03T01:00:00Z");
    db.update("UPDATE repair_orders SET repair_deadline=? WHERE id=?", deadline, id);
    var before =
        db.queryForMap(
            "SELECT status,source_version,restriction_start,pending_action FROM repair_orders WHERE"
                + " id=?",
            id);
    var policy = new RepairTimeoutPolicy(java.time.Duration.ofHours(1), 1000);
    var earlier =
        new RepairEscalationService(
            repository,
            transactions,
            policy,
            java.time.Clock.fixed(deadline.toInstant().minusNanos(1000), java.time.ZoneOffset.UTC));
    assertFalse(earlier.escalate(id));
    var atBoundary =
        new RepairEscalationService(
            repository,
            transactions,
            policy,
            java.time.Clock.fixed(deadline.toInstant(), java.time.ZoneOffset.UTC));
    assertTrue(atBoundary.escalate(id));
    assertFalse(atBoundary.escalate(id));
    assertEquals(
        before,
        db.queryForMap(
            "SELECT status,source_version,restriction_start,pending_action FROM repair_orders WHERE"
                + " id=?",
            id));
    assertTrue(RESTRICTIONS.get("M3/" + id).path("active").asBoolean());
    assertEquals(1, COMMANDS.get());
    var event = db.queryForMap("SELECT * FROM repair_escalations WHERE repair_id=?", id);
    assertEquals("AVAILABLE", event.get("notification_status"));
    assertNotNull(event.get("request_id"));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM repair_escalations", Integer.class));
  }

  @Test
  void m3EscalationRechecksCompletedStateAndRecoversLegacyDeadlineAfterRestart() throws Exception {
    long id = reportedRepair();
    var start = java.time.OffsetDateTime.parse("2026-11-01T00:00:00Z");
    db.update("UPDATE repair_orders SET created_at=?,repair_deadline=NULL WHERE id=?", start, id);
    var fixed = java.time.Clock.fixed(start.plusHours(2).toInstant(), java.time.ZoneOffset.UTC);
    var oneHour = new RepairTimeoutPolicy(java.time.Duration.ofHours(1), 1000);
    var restarted = new RepairEscalationService(repository, transactions, oneHour, fixed);
    assertTrue(restarted.escalate(id));
    var saved =
        db.queryForObject(
            "SELECT repair_deadline FROM repair_orders WHERE id=?",
            java.time.OffsetDateTime.class,
            id);
    assertEquals(start.plusHours(1).toInstant(), saved.toInstant());
    assertFalse(
        new RepairEscalationService(
                repository,
                transactions,
                new RepairTimeoutPolicy(java.time.Duration.ofDays(7), 1000),
                fixed)
            .escalate(id));
    assertEquals(
        saved,
        db.queryForObject(
            "SELECT repair_deadline FROM repair_orders WHERE id=?",
            java.time.OffsetDateTime.class,
            id));
    for (String state : java.util.List.of("AWAITING_ACCEPTANCE", "ACCEPTED", "PROCESSING")) {
      long done = reportedRepair();
      db.update(
          "UPDATE repair_orders SET status=?,pending_action='ACCEPT',repair_deadline=? WHERE id=?",
          state,
          start,
          done);
      assertFalse(restarted.escalate(done));
    }
    assertEquals(1, db.queryForObject("SELECT count(*) FROM repair_escalations", Integer.class));
  }

  @Test
  void m3ConcurrentEscalationsPersistExactlyOneAdministratorReminder() throws Exception {
    long id = reportedRepair();
    db.update(
        "UPDATE repair_orders SET repair_deadline=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=?",
        id);
    var barrier = new java.util.concurrent.CyclicBarrier(4);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 4; i++)
        futures.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return escalation.escalate(id);
                }));
      int changed = 0;
      for (var f : futures) if (f.get()) changed++;
      assertEquals(1, changed);
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM repair_escalations WHERE repair_id=?", Integer.class, id));
    assertEquals(
        "REPORTED",
        db.queryForObject("SELECT status FROM repair_orders WHERE id=?", String.class, id));
    assertEquals(0, COMMANDS.get());
  }

  @Test
  void m3FailedReminderPersistenceRollsBackAndCanRetry() throws Exception {
    long id = reportedRepair();
    db.update(
        "UPDATE repair_orders SET created_at=CURRENT_TIMESTAMP-INTERVAL '2"
            + " days',repair_deadline=NULL WHERE id=?",
        id);
    db.execute(
        "CREATE FUNCTION fail_reminder() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'reminder failure'; END $$");
    db.execute(
        "CREATE TRIGGER fail_reminder BEFORE INSERT ON repair_escalations FOR EACH ROW EXECUTE"
            + " FUNCTION fail_reminder()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class, () -> escalation.escalate(id));
      assertNull(
          db.queryForObject(
              "SELECT repair_deadline FROM repair_orders WHERE id=?",
              java.time.OffsetDateTime.class,
              id));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM repair_escalations", Integer.class));
      assertEquals(
          "REPORTED",
          db.queryForObject("SELECT status FROM repair_orders WHERE id=?", String.class, id));
    } finally {
      db.execute("DROP TRIGGER fail_reminder ON repair_escalations");
      db.execute("DROP FUNCTION fail_reminder()");
    }
    assertTrue(escalation.escalate(id));
  }

  @Test
  void m3ScannerPersistsReminderOnceAndInboxRequiresAdministrator() throws Exception {
    long id = reportedRepair();
    long ended = reportedRepair();
    db.update("UPDATE repair_orders SET repair_deadline=CURRENT_TIMESTAMP-INTERVAL '1 day'");
    db.update("UPDATE repair_orders SET status='AWAITING_ACCEPTANCE' WHERE id=?", ended);
    repairScanner.scan();
    repairScanner.scan();
    var events = parse(call("GET", "/repair-orders/escalations", null, "admin", null, false));
    assertEquals(1, events.size());
    assertEquals(id, events.get(0).path("repairId").asLong());
    assertEquals("AVAILABLE", events.get(0).path("notificationStatus").asText());
    assertEquals(
        403, call("GET", "/repair-orders/escalations", null, "student", null, false).statusCode());
    assertEquals(
        403, call("GET", "/repair-orders/escalations", null, "tech", null, false).statusCode());
    assertEquals(
        401, call("GET", "/repair-orders/escalations", null, null, null, false).statusCode());
    assertEquals(
        400,
        call("GET", "/repair-orders/escalations?page=0", null, "admin", null, false).statusCode());
    assertEquals(
        0,
        parse(
                call(
                    "GET",
                    "/repair-orders/escalations?page=2&pageSize=1",
                    null,
                    "admin",
                    null,
                    false))
            .size());
    assertEquals(0, COMMANDS.get());
  }

  @Test
  void m3ScannerFailureCannotStarveLaterPagesAndFreshScannerRecovers() {
    db.update(
        """
        INSERT INTO repair_orders(classroom_id,classroom_name,reporter_id,reporter_name,description,status,repair_deadline)
        SELECT 1,'A101',1,'student','scan','REPORTED',CURRENT_TIMESTAMP-INTERVAL '1 day'
        FROM generate_series(1,101)
        """);
    db.execute(
        "CREATE FUNCTION fail_first_reminder() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.repair_id=1 THEN RAISE EXCEPTION 'first reminder failure'; END IF; RETURN NEW;"
            + " END $$");
    db.execute(
        "CREATE TRIGGER fail_first_reminder BEFORE INSERT ON repair_escalations FOR EACH ROW"
            + " EXECUTE FUNCTION fail_first_reminder()");
    try {
      repairScanner.scan();
      assertEquals(
          100, db.queryForObject("SELECT count(*) FROM repair_escalations", Integer.class));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT count(*) FROM repair_escalations WHERE repair_id=101", Integer.class));
      repairScanner.scan();
      assertEquals(
          100, db.queryForObject("SELECT count(*) FROM repair_escalations", Integer.class));
    } finally {
      db.execute("DROP TRIGGER fail_first_reminder ON repair_escalations");
      db.execute("DROP FUNCTION fail_first_reminder()");
    }
    new RepairTimeoutScanner(repository, escalation, java.time.Clock.systemUTC()).scan();
    assertEquals(101, db.queryForObject("SELECT count(*) FROM repair_escalations", Integer.class));
  }

  @Test
  void m3CompletionWinningRowLockPreventsEscalationFromStaleCandidate() throws Exception {
    long id = reportedRepair();
    assignRepair(id);
    call("POST", "/repair-orders/" + id + "/start", Map.of(), "tech", null, true);
    db.update(
        "UPDATE repair_orders SET repair_deadline=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=?",
        id);
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var future =
          new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
      transactions.executeWithoutResult(
          status -> {
            repository.locked("M3", id);
            future.set(pool.submit(() -> escalation.escalate(id)));
            db.update("UPDATE repair_orders SET status='AWAITING_ACCEPTANCE' WHERE id=?", id);
          });
      assertFalse(future.get().get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertEquals(0, db.queryForObject("SELECT count(*) FROM repair_escalations", Integer.class));
    assertTrue(RESTRICTIONS.get("M3/" + id).path("active").asBoolean());
  }

  void assignRepair(long id) throws Exception {
    assertEquals(
        200,
        call(
                "POST",
                "/repair-orders/" + id + "/assign",
                Map.of("technicianId", 4),
                "admin",
                null,
                true)
            .statusCode());
  }

  @Test
  void m3ReassignmentKeepsHistoryRejectsStaleOwnerAndRevokesOldTechnician() throws Exception {
    long id = reportedRepair();
    assignRepair(id);
    String path = "/repair-orders/" + id;
    assertEquals(
        400,
        call("POST", path + "/assign", Map.of("technicianId", 6), "admin", null, true)
            .statusCode());
    var body = Map.of("technicianId", 6, "expectedTechnicianId", 4, "reason", "人员调度");
    assertEquals(403, call("POST", path + "/assign", body, "student", null, true).statusCode());
    var first = call("POST", path + "/assign", body, "admin", "reassign-key", true);
    assertEquals(200, first.statusCode());
    assertEquals(
        first.body(), call("POST", path + "/assign", body, "admin", "reassign-key", true).body());
    assertEquals(409, call("POST", path + "/assign", body, "admin", null, true).statusCode());
    assertEquals(403, call("POST", path + "/start", Map.of(), "tech", null, true).statusCode());
    assertEquals(403, call("GET", path + "/history", null, "other", null, false).statusCode());
    assertEquals(403, call("GET", path + "/history", null, "tech", null, false).statusCode());
    var history = parse(call("GET", path + "/history", null, "student", null, false));
    assertEquals(2, history.size());
    assertEquals("REASSIGN", history.get(1).path("action").asText());
    assertEquals(4, history.get(1).path("previousTechnicianId").asLong());
    assertEquals(6, history.get(1).path("technicianId").asLong());
    assertEquals("人员调度", history.get(1).path("reason").asText());
    assertTrue(history.get(1).hasNonNull("createdAt"));
    assertEquals(
        "IN_PROGRESS",
        parse(call("POST", path + "/start", Map.of(), "othertech", null, true))
            .path("status")
            .asText());
    assertEquals(409, call("POST", path + "/assign", body, "admin", null, true).statusCode());
    assertEquals(1, COMMANDS.get());
  }

  @Test
  void m3ReturnRequiresServerSideReasonAndRetainsEveryReasonWithoutReleasingSeats()
      throws Exception {
    long id = reportedRepair();
    assignRepair(id);
    String path = "/repair-orders/" + id;
    call("POST", path + "/start", Map.of(), "tech", null, true);
    for (String reason : java.util.List.of("仍漏水", "再次检查")) {
      call("POST", path + "/complete", Map.of("note", "处理完成"), "tech", null, true);
      assertEquals(
          400,
          call("POST", path + "/return", Map.of("comment", "  "), "admin", null, true)
              .statusCode());
      assertEquals(
          "IN_PROGRESS",
          parse(call("POST", path + "/return", Map.of("comment", reason), "admin", null, true))
              .path("status")
              .asText());
      assertTrue(RESTRICTIONS.get("M3/" + id).path("active").asBoolean());
    }
    var h = parse(call("GET", path + "/history", null, "admin", null, false));
    assertEquals(3, h.size());
    assertEquals("仍漏水", h.get(1).path("reason").asText());
    assertEquals("再次检查", h.get(2).path("reason").asText());
    assertEquals(1, COMMANDS.get());
  }

  @Test
  void m3ConcurrentReassignmentsHaveOneWinnerAndOneHistoryEvent() throws Exception {
    long id = reportedRepair();
    assignRepair(id);
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Integer> reassign =
          () -> {
            barrier.await();
            return call(
                    "POST",
                    "/repair-orders/" + id + "/assign",
                    Map.of("technicianId", 6, "expectedTechnicianId", 4, "reason", "交接"),
                    "admin",
                    null,
                    true)
                .statusCode();
          };
      var a = pool.submit(reassign);
      var b = pool.submit(reassign);
      assertEquals(java.util.Set.of(200, 409), java.util.Set.of(a.get(), b.get()));
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM repair_history WHERE action='REASSIGN'", Integer.class));
    assertEquals(
        6L,
        db.queryForObject("SELECT technician_id FROM repair_orders WHERE id=?", Long.class, id));
  }

  @Test
  void m3IllegalTransitionsAreRejectedWithoutChangingStateOrCallingResource() throws Exception {
    var allowed =
        Map.of(
            "REPORTED",
            java.util.Set.of("assign"),
            "ASSIGNED",
            java.util.Set.of("assign", "start"),
            "IN_PROGRESS",
            java.util.Set.of("complete"),
            "AWAITING_ACCEPTANCE",
            java.util.Set.of("accept", "return"),
            "ACCEPTED",
            java.util.Set.<String>of(),
            "PROCESSING",
            java.util.Set.<String>of());
    for (var entry : allowed.entrySet()) {
      long id = reportedRepair();
      assignRepair(id);
      db.update("UPDATE repair_orders SET status=? WHERE id=?", entry.getKey(), id);
      for (String action : java.util.List.of("assign", "start", "complete", "accept", "return")) {
        if (entry.getValue().contains(action)) continue;
        Object body =
            switch (action) {
              case "assign" -> Map.of("technicianId", 6, "expectedTechnicianId", 4, "reason", "换班");
              case "complete" -> Map.of("note", "已完成");
              default -> Map.of("comment", "验收意见");
            };
        assertEquals(
            409,
            call(
                    "POST",
                    "/repair-orders/" + id + "/" + action,
                    body,
                    action.equals("start") || action.equals("complete") ? "tech" : "admin",
                    null,
                    true)
                .statusCode(),
            entry.getKey() + "/" + action);
        assertEquals(
            entry.getKey(),
            db.queryForObject("SELECT status FROM repair_orders WHERE id=?", String.class, id));
      }
    }
    assertEquals(0, COMMANDS.get());
  }

  @Test
  void m3HistoryFailureRollsBackAssignmentAndAllowsSameRequestToRetry() throws Exception {
    long id = reportedRepair();
    db.execute(
        "CREATE FUNCTION fail_repair_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'history failure'; END $$");
    db.execute(
        "CREATE TRIGGER fail_repair_history BEFORE INSERT ON repair_history FOR EACH ROW EXECUTE"
            + " FUNCTION fail_repair_history()");
    try {
      assertEquals(
          500,
          call(
                  "POST",
                  "/repair-orders/" + id + "/assign",
                  Map.of("technicianId", 4),
                  "admin",
                  "history-rollback",
                  true)
              .statusCode());
      assertEquals(
          "REPORTED",
          db.queryForObject("SELECT status FROM repair_orders WHERE id=?", String.class, id));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM repair_history", Integer.class));
      assertEquals(
          0,
          db.queryForObject(
              "SELECT count(*) FROM idempotency_results WHERE request_key='history-rollback'",
              Integer.class));
    } finally {
      db.execute("DROP TRIGGER fail_repair_history ON repair_history");
      db.execute("DROP FUNCTION fail_repair_history()");
    }
    assertEquals(
        200,
        call(
                "POST",
                "/repair-orders/" + id + "/assign",
                Map.of("technicianId", 4),
                "admin",
                "history-rollback",
                true)
            .statusCode());
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
      } else if (path.endsWith("/users")) {
        String query = exchange.getRequestURI().getQuery();
        LAST_USERS_QUERY.set(query);
        var records =
            users().values().stream()
                .filter(
                    user ->
                        !"role=TECHNICIAN".equals(query)
                            || ((java.util.List<?>) user.get("roles")).contains("TECHNICIAN"))
                .toList();
        respond(exchange, 200, records);
      } else if (path.contains("/restrictions/")) {
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
