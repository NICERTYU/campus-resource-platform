package cn.campus.workflow;

import static cn.campus.workflow.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultilevelApprovalIntegrationTest {
  @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:18.6");
  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.now());
  static final AtomicInteger REMOTE = new AtomicInteger(200);
  static final AtomicInteger LOST = new AtomicInteger();
  static final AtomicInteger CALLS = new AtomicInteger();
  static final Map<Long, JsonNode> RESTRICTIONS = new ConcurrentHashMap<>();
  static final AtomicReference<CountDownLatch> BLOCK = new AtomicReference<>();
  static final AtomicReference<CountDownLatch> ARRIVED = new AtomicReference<>();
  static final ExecutorService HTTP_THREADS = Executors.newCachedThreadPool();
  static final HttpServer RESOURCE = resourceServer();
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired OutboxProcessor outbox;
  @Autowired WorkflowRepository repo;
  @Autowired WorkflowService service;
  @Autowired ResourceClient client;
  @Autowired TransactionTemplate tx;
  @Autowired Clock clock;
  final HttpClient browser = HttpClient.newHttpClient();

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    Clock testClock() {
      return new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return Clock.fixed(instant(), zone);
        }

        public Instant instant() {
          return NOW.get();
        }
      };
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
    r.add("workflow.resource-url", () -> "http://localhost:" + RESOURCE.getAddress().getPort());
    r.add("workflow.module-key", () -> "multilevel-test-secret-at-least-32-characters");
    r.add("workflow.retry-delay-ms", () -> "3600000");
    r.add("workflow.approval.expiry-scan-ms", () -> "3600000");
    r.add("workflow.approval.first-reviewer-ids", () -> "11");
    r.add("workflow.approval.final-reviewer-ids", () -> "12");
  }

  @BeforeEach
  void clean() {
    db.execute(
        "TRUNCATE"
            + " application_history,idempotency_results,outbox,audit_logs,classroom_applications,repair_orders"
            + " RESTART IDENTITY");
    // PostgreSQL TIMESTAMPTZ retains microseconds, so equality tests use the same precision.
    NOW.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    REMOTE.set(200);
    LOST.set(0);
    CALLS.set(0);
    RESTRICTIONS.clear();
    BLOCK.set(null);
    ARRIVED.set(null);
  }

  @AfterAll
  static void close() {
    RESOURCE.stop(0);
    HTTP_THREADS.shutdownNow();
  }

  @Test
  void twoStagesRequireAuthorizedActorsFreshRevisionAndKeepImmutableHistory() throws Exception {
    var a = create();
    long id = a.path("id").asLong();
    assertEquals(2, a.path("totalSteps").asInt());
    assertEquals(400, action(id, "approve", "first", Map.of(), null).statusCode());
    assertEquals(403, review(a, "approve", "final", null).statusCode());
    assertEquals(403, review(a, "approve", "otheradmin", null).statusCode());
    var first = review(a, "approve", "first", "first-key");
    assertEquals(200, first.statusCode());
    assertEquals(2, parse(first).path("currentStep").asInt());
    assertEquals("PENDING", parse(first).path("status").asText());
    assertEquals(0, CALLS.get());
    assertEquals(first.body(), review(a, "approve", "first", "first-key").body());
    assertEquals(409, review(a, "approve", "first", null).statusCode());
    a = parse(first);
    assertEquals(403, review(a, "approve", "first", null).statusCode());
    var approved = review(a, "approve", "final", null);
    assertEquals("APPROVED", parse(approved).path("status").asText());
    assertTrue(RESTRICTIONS.get(id).path("active").asBoolean());
    var h =
        parse(call("GET", "/classroom-applications/" + id + "/history", null, "applicant", null));
    assertEquals(3, h.size());
    assertEquals(11, h.get(1).path("actorId").asLong());
    assertEquals(12, h.get(2).path("actorId").asLong());
    assertEquals(2, h.get(2).path("step").asInt());
    assertEquals("review", h.get(2).path("comment").asText());
    assertTrue(h.get(2).hasNonNull("createdAt"));
    assertEquals(
        403,
        call("GET", "/classroom-applications/" + id + "/history", null, "other", null)
            .statusCode());
    assertEquals(
        401,
        call("GET", "/classroom-applications/" + id + "/history", null, null, null).statusCode());
  }

  @Test
  void returnResubmitRejectAndDeadlineCannotBeExtended() throws Exception {
    var a = create();
    var deadline = a.path("approvalDeadline").asText();
    a = parse(review(a, "return", "first", null));
    assertEquals("RETURNED", a.path("status").asText());
    assertEquals(
        403,
        action(
                a.path("id").asLong(),
                "resubmit",
                "moved",
                Map.of("purpose", "updated", "revision", a.path("revision").asLong()),
                null)
            .statusCode());
    assertEquals(
        403,
        action(
                a.path("id").asLong(),
                "resubmit",
                "otheradmin",
                Map.of("purpose", "updated", "revision", a.path("revision").asLong()),
                null)
            .statusCode());
    a =
        parse(
            action(
                a.path("id").asLong(),
                "resubmit",
                "applicant",
                Map.of("purpose", "updated", "revision", a.path("revision").asLong()),
                null));
    assertEquals(deadline, a.path("approvalDeadline").asText());
    a = parse(review(a, "approve", "first", null));
    a = parse(review(a, "return", "final", null));
    assertEquals(1, a.path("currentStep").asInt());
    assertEquals("PENDING", a.path("status").asText());
    assertEquals("REJECTED", parse(review(a, "reject", "first", null)).path("status").asText());
    assertEquals(0, CALLS.get());
  }

  @Test
  void concurrentDistinctRequestsCannotSkipSecondStage() throws Exception {
    var a = create();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var jobs =
          pool.invokeAll(
              List.of(
                  () -> review(a, "approve", "first", "a"),
                  () -> review(a, "approve", "first", "b")));
      var statuses = new ArrayList<Integer>();
      for (var job : jobs) statuses.add(((HttpResponse<?>) job.get()).statusCode());
      Collections.sort(statuses);
      assertEquals(List.of(200, 409), statuses);
      assertEquals(
          2, db.queryForObject("SELECT current_step FROM classroom_applications", Integer.class));
      assertEquals(0, CALLS.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void conflictAndRestartRetryKeepProcessingAndOneSourceVersion() throws Exception {
    var a = parse(review(create(), "approve", "first", null));
    long id = a.path("id").asLong();
    REMOTE.set(409);
    var pending = review(a, "approve", "final", "final-key");
    assertEquals(202, pending.statusCode());
    assertEquals("PROCESSING", parse(pending).path("status").asText());
    assertTrue(parse(pending).hasNonNull("lastError"));
    REMOTE.set(200);
    retryFromRestart();
    retryFromRestart();
    assertEquals("APPROVED", state(id));
    assertEquals(1, RESTRICTIONS.size());
    assertEquals(1, RESTRICTIONS.get(id).path("version").asLong());
    assertEquals(pending.body(), review(a, "approve", "final", "final-key").body());
  }

  @Test
  void deadlineBoundaryAndRestartSweepExpirePendingAndReturnedOnlyOnce() throws Exception {
    var pending = create();
    var returned = parse(review(create(), "return", "first", null));
    db.update(
        "UPDATE classroom_applications SET approval_deadline=?",
        OffsetDateTime.now(clock).plusSeconds(1));
    new ApplicationExpiryScanner(repo, service, clock).scan();
    assertEquals("PENDING", state(pending.path("id").asLong()));
    NOW.set(NOW.get().plusSeconds(1));
    assertEquals(409, review(pending, "approve", "first", null).statusCode());
    new ApplicationExpiryScanner(repo, service, clock).scan();
    new ApplicationExpiryScanner(repo, service, clock).scan();
    assertEquals("EXPIRED", state(pending.path("id").asLong()));
    assertEquals("EXPIRED", state(returned.path("id").asLong()));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM application_history WHERE action='EXPIRE'", Integer.class));
    assertEquals(0, CALLS.get());
  }

  @Test
  void lostResponseThenExpiryReleasesOnlyMatchingSourceAndKeepsTombstone() throws Exception {
    var a = parse(review(create(), "approve", "first", null));
    long id = a.path("id").asLong();
    LOST.set(1);
    assertEquals(202, review(a, "approve", "final", null).statusCode());
    assertTrue(RESTRICTIONS.get(id).path("active").asBoolean());
    db.update(
        "UPDATE classroom_applications SET approval_deadline=? WHERE id=?",
        OffsetDateTime.now(clock),
        id);
    new ApplicationExpiryScanner(repo, service, clock).scan();
    retryFromRestart();
    assertEquals("EXPIRED", state(id));
    assertFalse(RESTRICTIONS.get(id).path("active").asBoolean());
    assertEquals(2, RESTRICTIONS.get(id).path("version").asInt());
    assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM outbox WHERE status='SUPERSEDED'", Integer.class));
  }

  @Test
  void inFlightFinalApprovalCannotWinAgainstExpiryCompensation() throws Exception {
    var a = parse(review(create(), "approve", "first", null));
    long id = a.path("id").asLong();
    var arrived = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ARRIVED.set(arrived);
    BLOCK.set(release);
    var pool = Executors.newSingleThreadExecutor();
    try {
      var future = pool.submit(() -> review(a, "approve", "final", null));
      assertTrue(arrived.await(5, TimeUnit.SECONDS));
      db.update(
          "UPDATE classroom_applications SET approval_deadline=? WHERE id=?",
          OffsetDateTime.now(clock),
          id);
      new ApplicationExpiryScanner(repo, service, clock).scan();
      release.countDown();
      future.get(5, TimeUnit.SECONDS);
      assertEquals("EXPIRED", state(id));
      assertFalse(RESTRICTIONS.get(id).path("active").asBoolean());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void withdrawalWhileUnavailableSupersedesApprovalAndRecoversAfterRestart() throws Exception {
    var a = parse(review(create(), "approve", "first", null));
    long id = a.path("id").asLong();
    REMOTE.set(503);
    assertEquals(202, review(a, "approve", "final", null).statusCode());
    assertEquals(202, action(id, "withdraw", "applicant", Map.of(), null).statusCode());
    REMOTE.set(200);
    retryFromRestart();
    assertEquals("WITHDRAWN", state(id));
    assertFalse(RESTRICTIONS.get(id).path("active").asBoolean());
    assertEquals(2, RESTRICTIONS.get(id).path("version").asInt());
  }

  @Test
  void expiredReleaseFailureDoesNotPretendResourcesWereReleased() throws Exception {
    var a = parse(review(create(), "approve", "first", null));
    long id = a.path("id").asLong();
    REMOTE.set(503);
    review(a, "approve", "final", null);
    db.update(
        "UPDATE classroom_applications SET approval_deadline=? WHERE id=?",
        OffsetDateTime.now(clock),
        id);
    new ApplicationExpiryScanner(repo, service, clock).scan();
    assertEquals("PROCESSING", state(id));
    assertEquals(
        "EXPIRE",
        db.queryForObject(
            "SELECT pending_action FROM classroom_applications WHERE id=?", String.class, id));
    REMOTE.set(200);
    retryFromRestart();
    assertEquals("EXPIRED", state(id));
  }

  @Test
  void upgradeFromV2PreservesLegacyApprovalAndDataAndReplaysCleanly() {
    String schema = "m2_upgrade";
    var initial =
        org.flywaydb.core.Flyway.configure()
            .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .target("2")
            .load();
    initial.migrate();
    try {
      db.update(
          "INSERT INTO"
              + " m2_upgrade.classroom_applications(classroom_id,classroom_name,applicant_id,applicant_name,start_time,end_time,purpose,status,comment)"
              + " VALUES(1,'A101',2,'applicant',CURRENT_TIMESTAMP+INTERVAL '2"
              + " days',CURRENT_TIMESTAMP+INTERVAL '3 days','legacy','APPROVED','keep')");
      var upgraded =
          org.flywaydb.core.Flyway.configure()
              .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
              .schemas(schema)
              .defaultSchema(schema)
              .locations("classpath:db/migration")
              .load();
      upgraded.migrate();
      var row = db.queryForMap("SELECT * FROM m2_upgrade.classroom_applications");
      assertEquals("APPROVED", row.get("status"));
      assertEquals("keep", row.get("comment"));
      assertEquals(1, row.get("total_steps"));
      assertNotNull(row.get("approval_deadline"));
      assertEquals(
          0,
          db.queryForObject("SELECT count(*) FROM m2_upgrade.application_history", Integer.class));
      assertEquals(0, upgraded.migrate().migrationsExecuted);
    } finally {
      db.execute("DROP SCHEMA m2_upgrade CASCADE");
    }
  }

  @Test
  void historyFailureRollsBackStageAndAudit() throws Exception {
    var a = create();
    db.execute(
        "CREATE FUNCTION fail_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'history failure'; END $$");
    db.execute(
        "CREATE TRIGGER fail_history BEFORE INSERT ON application_history FOR EACH ROW EXECUTE"
            + " FUNCTION fail_history()");
    try {
      assertEquals(500, review(a, "approve", "first", null).statusCode());
      assertEquals(
          1, db.queryForObject("SELECT current_step FROM classroom_applications", Integer.class));
      assertEquals(
          0L, db.queryForObject("SELECT revision FROM classroom_applications", Long.class));
      assertEquals(1, db.queryForObject("SELECT count(*) FROM application_history", Integer.class));
    } finally {
      db.execute("DROP TRIGGER fail_history ON application_history");
      db.execute("DROP FUNCTION fail_history()");
    }
  }

  void retryFromRestart() {
    db.update("UPDATE outbox SET due_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
    new OutboxProcessor(repo, tx, client, clock).retry();
  }

  String state(long id) {
    return db.queryForObject(
        "SELECT status FROM classroom_applications WHERE id=?", String.class, id);
  }

  JsonNode create() throws Exception {
    var date = LocalDate.now(clock).plusDays(2).toString();
    var response =
        call(
            "POST",
            "/classroom-applications",
            Map.of(
                "classroomId",
                1,
                "startTime",
                date + "T09:00:00+08:00",
                "endTime",
                date + "T10:00:00+08:00",
                "purpose",
                "seminar"),
            "applicant",
            null);
    assertEquals(201, response.statusCode(), response.body());
    return parse(response);
  }

  HttpResponse<String> review(JsonNode a, String action, String actor, String key)
      throws Exception {
    return action(
        a.path("id").asLong(),
        action,
        actor,
        Map.of("comment", "review", "revision", a.path("revision").asLong()),
        key);
  }

  HttpResponse<String> action(long id, String action, String actor, Object body, String key)
      throws Exception {
    return call("POST", "/classroom-applications/" + id + "/" + action, body, actor, key);
  }

  HttpResponse<String> call(String method, String path, Object body, String actor, String key)
      throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Content-Type", "application/json");
    if (actor != null) req.header("Cookie", "JSESSIONID=" + actor);
    if (key != null) req.header("Idempotency-Key", key);
    req.header("X-CSRF-TOKEN", "valid");
    return browser.send(
        req.method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static JsonNode parse(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  static HttpServer resourceServer() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.setExecutor(HTTP_THREADS);
      server.createContext("/internal/v1/", MultilevelApprovalIntegrationTest::resource);
      server.start();
      return server;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void resource(HttpExchange e) throws java.io.IOException {
    String path = e.getRequestURI().getPath();
    var body =
        e.getRequestMethod().equals("GET")
            ? null
            : JSON.readTree(
                new String(
                    e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    if (path.endsWith("/identity")) {
      String actor = body.path("cookie").asText().replace("JSESSIONID=", "");
      long id =
          switch (actor) {
            case "first" -> 11;
            case "final" -> 12;
            case "otheradmin" -> 13;
            case "applicant" -> 2;
            case "moved" -> 2;
            case "other" -> 4;
            default -> 0;
          };
      if (id == 0) {
        respond(e, 401, Map.of("code", "UNAUTHENTICATED", "message", "login"));
        return;
      }
      if (body.path("write").asBoolean() && !body.path("csrfToken").asText().equals("valid")) {
        respond(e, 403, Map.of("code", "CSRF_INVALID", "message", "csrf"));
        return;
      }
      respond(
          e,
          200,
          Map.of(
              "id",
              id,
              "username",
              actor,
              "displayName",
              actor,
              "department",
              actor.equals("final") || actor.equals("moved") ? "ART" : "CS",
              "roles",
              List.of(
                  actor.contains("admin") || actor.equals("first") || actor.equals("final")
                      ? "ADMIN"
                      : actor.equals("applicant") || actor.equals("moved")
                          ? "APPLICANT"
                          : "STUDENT")));
    } else if (path.contains("/classrooms/"))
      respond(
          e,
          200,
          Map.of(
              "id",
              1,
              "name",
              "A101",
              "building",
              "A",
              "floor",
              1,
              "department",
              "CS",
              "openTime",
              "08:00",
              "closeTime",
              "22:00",
              "enabled",
              true,
              "seatCount",
              24));
    else if (path.contains("/restrictions/")) {
      CALLS.incrementAndGet();
      if (REMOTE.get() != 200) {
        respond(
            e,
            REMOTE.get(),
            Map.of("code", "RESOURCE_CONFLICT", "message", "resource unavailable or occupied"));
        return;
      }
      long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
      var current =
          RESTRICTIONS.compute(
              id,
              (k, old) ->
                  old == null || body.path("version").asLong() >= old.path("version").asLong()
                      ? body
                      : old);
      if (body.path("active").asBoolean() && BLOCK.get() != null) {
        ARRIVED.get().countDown();
        try {
          BLOCK.get().await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
      if (LOST.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
        e.close();
        return;
      }
      respond(
          e,
          200,
          Map.of(
              "version",
              current.path("version").asLong(),
              "active",
              current.path("active").asBoolean(),
              "applied",
              true));
    } else respond(e, 404, Map.of("code", "NOT_FOUND"));
  }

  static void respond(HttpExchange e, int status, Object body) throws java.io.IOException {
    byte[] bytes = JSON.writeValueAsString(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }
}
