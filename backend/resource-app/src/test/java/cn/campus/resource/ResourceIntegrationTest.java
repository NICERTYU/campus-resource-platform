package cn.campus.resource;

import static cn.campus.resource.Models.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResourceIntegrationTest {
  static final String MODULE_KEY = "resource-integration-module-key-32-characters";
  static final AtomicReference<Instant> TIME =
      new AtomicReference<>(Instant.parse("2026-10-12T01:50:00Z"));
  final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");
  ConfigurableApplicationContext app;
  ResourceService service;
  JdbcTemplate db;
  String base;
  final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    Clock controlledClock() {
      return new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return this;
        }

        public Instant instant() {
          return TIME.get();
        }
      };
    }
  }

  @BeforeAll
  void start() {
    postgres.start();
    restart();
  }

  void restart() {
    app =
        new SpringApplication(ResourceApplication.class, TestClock.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--campus.module-key=" + MODULE_KEY,
                "--campus.timeout-scan-ms=3600000");
    service = app.getBean(ResourceService.class);
    db = app.getBean(JdbcTemplate.class);
    base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (app != null) app.close();
    postgres.stop();
  }

  @BeforeEach
  void clean() {
    TIME.set(Instant.parse("2026-10-12T01:50:00Z"));
    db.execute(
        "TRUNCATE"
            + " audit_logs,idempotency_results,check_in_attempts,check_in_codes,violations,restrictions,seat_slots,reservations,seats,classrooms,user_roles,users"
            + " RESTART IDENTITY CASCADE");
    db.update("UPDATE settings SET max_reservation_hours=4 WHERE id=1");
    app.getBean(DemoSeeder.class).seed();
  }

  User user(String username) {
    return service.byUsername(username);
  }

  OffsetDateTime from() {
    return OffsetDateTime.parse("2026-10-12T10:00:00+08:00");
  }

  ReserveRequest booking(long seat) {
    return new ReserveRequest(seat, from(), from().plusHours(1));
  }

  RestrictionInput restriction(long version, boolean active, long actor) {
    return new RestrictionInput(
        version, active, 1, from(), from().plusHours(1), actor, "integration-command");
  }

  @Test
  void repeatableSeedingAndMigrationPreserveData() {
    app.getBean(DemoSeeder.class).seed();
    assertEquals(6, db.queryForObject("SELECT count(*) FROM users", Integer.class));
    assertEquals(72, db.queryForObject("SELECT count(*) FROM seats", Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL",
            Integer.class));
    db.update("UPDATE users SET display_name='Updated' WHERE username='student'");
    app.getBean(DemoSeeder.class).seed();
    assertEquals("Updated", user("student").displayName());
  }

  @Test
  void versionOneDatabaseUpgradesWithoutLosingBusinessData() {
    var v1 =
        org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("resource_upgrade")
            .defaultSchema("resource_upgrade")
            .locations("classpath:db/migration")
            .target("1")
            .load();
    try {
      v1.migrate();
      db.update(
          "INSERT INTO resource_upgrade.users(username,password_hash,display_name,department)"
              + " VALUES('retained','hash','Existing student','CS')");
      var latest =
          org.flywaydb.core.Flyway.configure()
              .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
              .schemas("resource_upgrade")
              .defaultSchema("resource_upgrade")
              .locations("classpath:db/migration")
              .load();
      latest.migrate();
      assertEquals(
          "Existing student",
          db.queryForObject(
              "SELECT display_name FROM resource_upgrade.users WHERE username='retained'",
              String.class));
      assertEquals(
          2,
          db.queryForObject(
              "SELECT count(*) FROM resource_upgrade.flyway_schema_history WHERE success AND"
                  + " version IS NOT NULL",
              Integer.class));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT count(*) FROM pg_indexes WHERE schemaname='resource_upgrade' AND"
                  + " indexname='reservations_due'",
              Integer.class));
      latest.validate();
    } finally {
      db.execute("DROP SCHEMA IF EXISTS resource_upgrade CASCADE");
    }
  }

  @Test
  void reservationsAreIdempotentAndUserCannotOverlapOrAccessOtherDepartment() throws Exception {
    Object first = service.reserve(user("student"), booking(1), "key", "r1");
    Object repeated = service.reserve(user("student"), booking(1), "key", "r2");
    var transportJson = app.getBean(tools.jackson.databind.ObjectMapper.class);
    assertEquals(
        transportJson.writeValueAsString(first), transportJson.writeValueAsString(repeated));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM reservations", Integer.class));
    assertEquals(
        409,
        assertThrows(
                ApiError.class, () -> service.reserve(user("student"), booking(2), "key", "r3"))
            .status);
    assertEquals(
        409,
        assertThrows(ApiError.class, () -> service.reserve(user("student"), booking(2), null, "r3"))
            .status);
    assertEquals(
        403,
        assertThrows(
                ApiError.class, () -> service.reserve(user("outsider"), booking(25), null, "r3"))
            .status);
    Reservation next =
        (Reservation)
            service.reserve(
                user("student"),
                new ReserveRequest(1, from().plusHours(1), from().plusHours(2)),
                null,
                "r4");
    assertEquals("PENDING", next.status());
  }

  @Test
  void wrongOwnerCannotCancelAndCheckedInReservationCanCancelBeforeEnd() {
    Reservation item = (Reservation) service.reserve(user("student"), booking(1), null, "r1");
    assertEquals(
        403,
        assertThrows(ApiError.class, () -> service.cancel(user("student2"), item.id(), null, "r2"))
            .status);
    assertEquals(
        400,
        assertThrows(
                ApiError.class,
                () -> service.checkIn(user("student"), item.id(), "000000", null, "r3"))
            .status);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT failures FROM check_in_attempts WHERE reservation_id=?",
            Integer.class,
            item.id()));
    assertEquals(
        "CHECKED_IN",
        ((Reservation) service.checkIn(user("student"), item.id(), "246810", null, "r4")).status());
    assertEquals(
        "CANCELLED",
        ((Reservation) service.cancel(user("student"), item.id(), null, "r5")).status());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM seat_slots", Integer.class));
  }

  @Test
  void wrongCodeRateLimitDoesNotRollBackFailures() {
    Reservation item = (Reservation) service.reserve(user("student"), booking(1), null, "r1");
    for (int i = 0; i < 5; i++)
      assertEquals(
          400,
          assertThrows(
                  ApiError.class,
                  () -> service.checkIn(user("student"), item.id(), "000000", null, "r2"))
              .status);
    assertEquals(
        429,
        assertThrows(
                ApiError.class,
                () -> service.checkIn(user("student"), item.id(), "246810", null, "r3"))
            .status);
  }

  @Test
  void timeoutIsIdempotentAndReleasesEntireReservation() {
    Reservation item =
        (Reservation)
            service.reserve(
                user("student"), new ReserveRequest(1, from(), from().plusHours(4)), null, "r1");
    TIME.set(from().plusMinutes(15).toInstant());
    service.sweep();
    service.sweep();
    assertEquals("NO_SHOW", service.reservation(item.id()).status());
    assertEquals(1, service.violations(user("student")).size());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM seat_slots", Integer.class));
    assertThrows(
        ApiError.class, () -> service.checkIn(user("student"), item.id(), "246810", null, "r3"));
  }

  @Test
  void maintenanceKeepsExistingBookingBlocksCheckInAndWaivesTimeoutViolation() {
    Reservation item = (Reservation) service.reserve(user("student"), booking(1), null, "r1");
    service.restriction(
        "M3",
        1,
        new RestrictionInput(1, true, 1, from(), null, user("technician").id(), "repair-start"));
    assertEquals("PENDING", service.reservation(item.id()).status());
    assertEquals(
        409,
        assertThrows(
                ApiError.class,
                () -> service.checkIn(user("student"), item.id(), "246810", null, "r2"))
            .status);
    TIME.set(from().plusMinutes(15).toInstant());
    service.sweep();
    assertEquals("CANCELLED", service.reservation(item.id()).status());
    assertEquals("MAINTENANCE", service.reservation(item.id()).cancelReason());
    assertTrue(service.violations(user("student")).isEmpty());
  }

  @Test
  void maintenanceBeginningAtDeadlineWaivesViolationButLaterStartDoesNot() {
    Reservation boundary =
        (Reservation) service.reserve(user("student"), booking(1), null, "boundary-booking");
    TIME.set(from().plusMinutes(15).toInstant());
    service.restriction(
        "M3",
        11,
        new RestrictionInput(
            1, true, 1, from(), null, user("technician").id(), "boundary-maintenance"));
    assertEquals("CANCELLED", service.reservation(boundary.id()).status());
    assertEquals("MAINTENANCE", service.reservation(boundary.id()).cancelReason());
    assertTrue(service.violations(user("student")).isEmpty());
    TIME.set(Instant.parse("2026-10-12T01:50:00Z"));
    Reservation late =
        (Reservation) service.reserve(user("student2"), booking(25), null, "late-booking");
    TIME.set(from().plusMinutes(16).toInstant());
    service.restriction(
        "M3",
        12,
        new RestrictionInput(
            1, true, 2, from(), null, user("technician").id(), "late-maintenance"));
    assertEquals("NO_SHOW", service.reservation(late.id()).status());
    assertEquals(1, service.violations(user("student2")).size());
  }

  @Test
  void scheduledFailureOnOneRecordDoesNotBlockHealthyRoomPeer() {
    Reservation broken =
        (Reservation) service.reserve(user("student"), booking(1), null, "broken-booking");
    Reservation healthy =
        (Reservation) service.reserve(user("student2"), booking(2), null, "healthy-booking");
    TIME.set(from().plusMinutes(15).toInstant());
    db.execute(
        "ALTER TABLE audit_logs ADD CONSTRAINT force_single_failure"
            + " CHECK(action<>'RESERVATION_NO_SHOW' OR object_id<>"
            + broken.id()
            + ")");
    try {
      service.sweep();
      assertEquals("PENDING", service.reservation(broken.id()).status());
      assertEquals("NO_SHOW", service.reservation(healthy.id()).status());
      assertTrue(service.violations(user("student")).isEmpty());
      assertEquals(1, service.violations(user("student2")).size());
      assertEquals(
          1,
          db.queryForObject(
              "SELECT count(*) FROM seat_slots WHERE reservation_id=?",
              Integer.class,
              broken.id()));
      assertEquals(
          0,
          db.queryForObject(
              "SELECT count(*) FROM seat_slots WHERE reservation_id=?",
              Integer.class,
              healthy.id()));
    } finally {
      db.execute("ALTER TABLE audit_logs DROP CONSTRAINT force_single_failure");
    }
    service.sweep();
    assertEquals("NO_SHOW", service.reservation(broken.id()).status());
    assertEquals(1, service.violations(user("student")).size());
    assertEquals(1, service.violations(user("student2")).size());
  }

  @Test
  void sourceRetentionTombstonesAndParameterConflictsAreEnforced() {
    var admin = user("admin");
    var technician = user("technician");
    RestrictionInput approval = restriction(1, true, admin.id());
    assertTrue(service.restriction("M2", 9, approval).applied());
    assertFalse(service.restriction("M2", 9, approval).applied());
    service.restriction(
        "M3", 1, new RestrictionInput(1, true, 1, from(), null, technician.id(), "m3a"));
    service.restriction(
        "M3", 2, new RestrictionInput(1, true, 1, from(), null, technician.id(), "m3b"));
    service.restriction("M2", 9, restriction(2, false, user("applicant").id()));
    assertFalse(service.restriction("M2", 9, approval).active());
    service.restriction(
        "M3", 1, new RestrictionInput(2, false, 1, from(), null, admin.id(), "m3a-release"));
    assertEquals(
        List.of("M3"),
        service
            .availability(user("student"), 1, from(), from().plusHours(1), null)
            .getFirst()
            .restrictionTypes());
    assertEquals(
        409,
        assertThrows(
                ApiError.class,
                () ->
                    service.restriction(
                        "M3",
                        2,
                        new RestrictionInput(1, false, 1, from(), null, admin.id(), "bad-version")))
            .status);
    service.restriction(
        "M3", 2, new RestrictionInput(2, false, 1, from(), null, admin.id(), "m3b-release"));
    assertTrue(
        service
            .availability(user("student"), 1, from(), from().plusHours(1), null)
            .getFirst()
            .available());
    assertEquals(
        3, db.queryForObject("SELECT count(*) FROM restrictions WHERE NOT active", Integer.class));
  }

  @Test
  void concurrentSameSeatRequestsHaveOneWinner() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> first =
          () ->
              attempt(
                  barrier,
                  () -> service.reserve(user("student"), booking(1), null, "concurrent-1"));
      Callable<Boolean> second =
          () ->
              attempt(
                  barrier,
                  () -> service.reserve(user("student2"), booking(1), null, "concurrent-2"));
      var futures = pool.invokeAll(List.of(first, second));
      assertNotEquals(futures.get(0).get(), futures.get(1).get());
    }
    assertEquals(1, db.queryForObject("SELECT count(*) FROM reservations", Integer.class));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM seat_slots", Integer.class));
  }

  @Test
  void concurrentPersonalBookingAndApprovalCannotBothOccupyRoom() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          pool.invokeAll(
              List.<Callable<Boolean>>of(
                  () ->
                      attempt(
                          barrier,
                          () -> service.reserve(user("student"), booking(1), null, "race-m1")),
                  () ->
                      attempt(
                          barrier,
                          () ->
                              service.restriction(
                                  "M2", 99, restriction(1, true, user("admin").id())))));
      assertNotEquals(futures.get(0).get(), futures.get(1).get());
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT (SELECT count(*) FROM reservations)+(SELECT count(*) FROM restrictions WHERE"
                + " active)",
            Integer.class));
  }

  boolean attempt(CyclicBarrier barrier, java.util.function.Supplier<Object> action)
      throws Exception {
    barrier.await(10, TimeUnit.SECONDS);
    try {
      action.get();
      return true;
    } catch (ApiError error) {
      assertEquals(409, error.status);
      return false;
    }
  }

  @Test
  void checkInAndTimeoutRaceRespectBothSidesOfDeadline() throws Exception {
    Reservation checked =
        (Reservation) service.reserve(user("student"), booking(1), null, "checked-race");
    Reservation missed =
        (Reservation) service.reserve(user("student2"), booking(2), null, "missed-race");
    TIME.set(from().plusMinutes(15).minusSeconds(1).toInstant());
    try (var pool = Executors.newFixedThreadPool(2)) {
      CyclicBarrier before = new CyclicBarrier(2);
      var beforeResults =
          pool.invokeAll(
              List.<Callable<Boolean>>of(
                  () ->
                      attempt(
                          before,
                          () ->
                              service.checkIn(
                                  user("student"),
                                  checked.id(),
                                  "246810",
                                  null,
                                  "before-deadline")),
                  () ->
                      attempt(
                          before,
                          () -> {
                            service.sweep();
                            return true;
                          })));
      assertTrue(beforeResults.get(0).get());
      assertTrue(beforeResults.get(1).get());
      assertEquals("CHECKED_IN", service.reservation(checked.id()).status());
      TIME.set(from().plusMinutes(15).toInstant());
      CyclicBarrier at = new CyclicBarrier(2);
      var atResults =
          pool.invokeAll(
              List.<Callable<Boolean>>of(
                  () ->
                      attempt(
                          at,
                          () ->
                              service.checkIn(
                                  user("student2"), missed.id(), "246810", null, "at-deadline")),
                  () ->
                      attempt(
                          at,
                          () -> {
                            service.sweep();
                            return true;
                          })));
      assertFalse(atResults.get(0).get());
      assertTrue(atResults.get(1).get());
    }
    assertEquals("CHECKED_IN", service.reservation(checked.id()).status());
    assertEquals("NO_SHOW", service.reservation(missed.id()).status());
    assertTrue(service.violations(user("student")).isEmpty());
    service.sweep();
    assertEquals(1, service.violations(user("student2")).size());
  }

  @Test
  void maintenanceAndReservationRacePreservesEarlierRecordAndBlocksNewWrites() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    boolean booked;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results =
          pool.invokeAll(
              List.<Callable<Boolean>>of(
                  () ->
                      attempt(
                          barrier,
                          () ->
                              service.reserve(
                                  user("student"), booking(1), null, "maintenance-race-booking")),
                  () ->
                      attempt(
                          barrier,
                          () ->
                              service.restriction(
                                  "M3",
                                  45,
                                  new RestrictionInput(
                                      1,
                                      true,
                                      1,
                                      from(),
                                      null,
                                      user("technician").id(),
                                      "maintenance-race-repair")))));
      booked = results.get(0).get();
      assertTrue(results.get(1).get());
    }
    assertEquals(
        booked ? 1 : 0, db.queryForObject("SELECT count(*) FROM reservations", Integer.class));
    if (booked) assertEquals("PENDING", service.mine(user("student")).getFirst().status());
    assertFalse(
        service
            .availability(user("student2"), 1, from(), from().plusHours(1), null)
            .getFirst()
            .available());
    assertEquals(
        409,
        assertThrows(
                ApiError.class,
                () -> service.reserve(user("student2"), booking(2), null, "after-maintenance"))
            .status);
    assertTrue(service.violations(user("student")).isEmpty());
  }

  @Test
  void auditFailureRollsBackReservationAndSlots() {
    db.execute(
        "ALTER TABLE audit_logs ADD CONSTRAINT force_failure CHECK(action<>'RESERVATION_CREATED')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> service.reserve(user("student"), booking(1), null, "r1"));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM reservations", Integer.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM seat_slots", Integer.class));
    } finally {
      db.execute("ALTER TABLE audit_logs DROP CONSTRAINT force_failure");
    }
  }

  @Test
  void timeoutTransactionFailureCanRetryWithoutPartialViolation() {
    Reservation item = (Reservation) service.reserve(user("student"), booking(1), null, "r1");
    TIME.set(from().plusMinutes(15).toInstant());
    db.execute(
        "ALTER TABLE audit_logs ADD CONSTRAINT force_timeout_failure"
            + " CHECK(action<>'RESERVATION_NO_SHOW')");
    try {
      service.sweep();
      assertEquals("PENDING", service.reservation(item.id()).status());
      assertTrue(service.violations(user("student")).isEmpty());
      assertEquals(1, db.queryForObject("SELECT count(*) FROM seat_slots", Integer.class));
    } finally {
      db.execute("ALTER TABLE audit_logs DROP CONSTRAINT force_timeout_failure");
    }
    service.sweep();
    assertEquals("NO_SHOW", service.reservation(item.id()).status());
    assertEquals(1, service.violations(user("student")).size());
  }

  @Test
  void maintenanceClockAndInternalActorAuthorizationAreEnforced() {
    assertEquals(
        403,
        assertThrows(
                ApiError.class,
                () -> service.restriction("M2", 1, restriction(1, true, user("student").id())))
            .status);
    service.restriction(
        "M3",
        1,
        new RestrictionInput(
            1, true, 1, from().plusDays(10), null, user("technician").id(), "m3-clock"));
    assertEquals(
        TIME.get(),
        db.queryForObject(
                "SELECT start_time FROM restrictions WHERE source_type='M3' AND source_id=1",
                java.sql.Timestamp.class)
            .toInstant());
    assertEquals(
        403,
        assertThrows(
                ApiError.class,
                () ->
                    service.restriction(
                        "M3",
                        1,
                        new RestrictionInput(
                            2, false, 1, from(), null, user("technician").id(), "m3-release")))
            .status);
  }

  @Test
  void unsafeRoomAndSeatConfigurationChangesAreRejected() {
    service.reserve(user("student"), booking(1), null, "r1");
    var room = service.room(1);
    assertThrows(
        ApiError.class,
        () ->
            service.updateRoom(
                user("admin"),
                1,
                new ClassroomInput(
                    room.name(),
                    room.building(),
                    room.floor(),
                    room.department(),
                    LocalTime.of(11, 0),
                    room.closeTime(),
                    true),
                null,
                "r2"));
    assertThrows(
        ApiError.class,
        () -> service.updateSeat(user("admin"), 1, new SeatInput("01", true, false), null, "r3"));
    assertEquals(
        403,
        assertThrows(ApiError.class, () -> service.updateSettings(user("student"), 8, null, "r4"))
            .status);
    assertThrows(
        ApiError.class, () -> service.updateSettings(user("admin"), 13, null, "too-many-hours"));
    service.updateSettings(user("admin"), 2, null, "r4");
    assertThrows(
        ApiError.class,
        () ->
            service.reserve(
                user("student2"), new ReserveRequest(2, from(), from().plusHours(3)), null, "r5"));
  }

  @Test
  void applicationRestartRecoversMissedTimeoutsWithoutDuplicateViolation() {
    Reservation item = (Reservation) service.reserve(user("student"), booking(1), null, "r1");
    app.close();
    TIME.set(from().plusMinutes(16).toInstant());
    restart();
    assertEquals("NO_SHOW", service.reservation(item.id()).status());
    service.sweep();
    assertEquals(1, service.violations(user("student")).size());
  }

  @Test
  void realHttpSessionCsrfIdentityAndModuleAuthentication() throws Exception {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var client = HttpClient.newBuilder().cookieHandler(cookies).build();
    assertEquals(401, send(client, "GET", "/api/v1/auth/me", null, null).statusCode());
    assertEquals(
        403,
        send(
                client,
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"student\",\"password\":\"Campus123!\"}",
                null)
            .statusCode());
    String csrf =
        json.readTree(send(client, "GET", "/api/v1/auth/csrf", null, null).body())
            .get("token")
            .asText();
    assertEquals(
        200,
        send(
                client,
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"student\",\"password\":\"Campus123!\"}",
                csrf)
            .statusCode());
    csrf =
        json.readTree(send(client, "GET", "/api/v1/auth/csrf", null, null).body())
            .get("token")
            .asText();
    var me = send(client, "GET", "/api/v1/auth/me", null, null);
    assertEquals("student", json.readTree(me.body()).get("username").asText());
    String bookingBody =
        json.writeValueAsString(
            Map.of(
                "seatId",
                1,
                "startTime",
                from().toString(),
                "endTime",
                from().plusHours(1).toString()));
    var reserveRequest =
        HttpRequest.newBuilder(URI.create(base + "/api/v1/reservations"))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", csrf)
            .header("Idempotency-Key", "http-repeat")
            .POST(HttpRequest.BodyPublishers.ofString(bookingBody))
            .build();
    var firstReservation = client.send(reserveRequest, HttpResponse.BodyHandlers.ofString());
    var replayReservation = client.send(reserveRequest, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, firstReservation.statusCode(), firstReservation.body());
    assertEquals(200, replayReservation.statusCode(), replayReservation.body());
    assertEquals(
        json.readTree(firstReservation.body()),
        json.readTree(replayReservation.body()),
        "Idempotent replay must preserve the exact HTTP representation including timestamp"
            + " offsets");
    assertTrue(json.readTree(firstReservation.body()).get("startTime").asText().endsWith("+08:00"));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM reservations", Integer.class));
    assertEquals(403, send(client, "POST", "/api/v1/reservations", "{}", null).statusCode());
    String cookie =
        cookies.getCookieStore().getCookies().stream()
            .filter(c -> c.getName().equals("JSESSIONID"))
            .map(c -> "JSESSIONID=" + c.getValue())
            .findFirst()
            .orElseThrow();
    String body =
        json.writeValueAsString(Map.of("cookie", cookie, "csrfToken", csrf, "write", true));
    var internal =
        HttpRequest.newBuilder(URI.create(base + "/internal/v1/identity"))
            .header("Content-Type", "application/json")
            .header("X-Module-Key", MODULE_KEY)
            .header("X-Request-ID", "http-identity")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var identity = client.send(internal, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, identity.statusCode(), identity.body());
    assertEquals("http-identity", identity.headers().firstValue("X-Request-ID").orElseThrow());
    assertEquals(403, send(client, "POST", "/internal/v1/identity", body, null).statusCode());
    assertEquals(200, send(client, "POST", "/api/v1/auth/logout", "{}", csrf).statusCode());
    assertEquals(401, client.send(internal, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void realHttpHistoryPaginationHasStableDistinctPagesAndRejectsInvalidParameters()
      throws Exception {
    Reservation first =
        (Reservation) service.reserve(user("student"), booking(1), null, "page-first");
    Reservation second =
        (Reservation)
            service.reserve(
                user("student"),
                new ReserveRequest(1, from().plusHours(1), from().plusHours(2)),
                null,
                "page-second");
    var student = login("student");
    var pageOne =
        json.readTree(
            send(
                    student,
                    "GET",
                    "/api/v1/reservations/mine?page=1&pageSize=1&statusGroup=current",
                    null,
                    null)
                .body());
    var pageTwo =
        json.readTree(
            send(
                    student,
                    "GET",
                    "/api/v1/reservations/mine?page=2&pageSize=1&statusGroup=current",
                    null,
                    null)
                .body());
    assertEquals(1, pageOne.size());
    assertEquals(second.id(), pageOne.get(0).get("id").asLong());
    assertEquals(1, pageTwo.size());
    assertEquals(first.id(), pageTwo.get(0).get("id").asLong());
    assertEquals(
        400,
        send(student, "GET", "/api/v1/reservations/mine?pageSize=201", null, null).statusCode());
    assertEquals(
        400, send(student, "GET", "/api/v1/reservations/mine?page=0", null, null).statusCode());
    assertEquals(
        400,
        send(student, "GET", "/api/v1/reservations/mine?statusGroup=unknown", null, null)
            .statusCode());
    service.cancel(user("student"), second.id(), null, "cancel-page-second");
    var filtered =
        json.readTree(
            send(
                    student,
                    "GET",
                    "/api/v1/reservations/mine?pageSize=1&statusGroup=current",
                    null,
                    null)
                .body());
    assertEquals(
        first.id(), filtered.get(0).get("id").asLong(), "Filtering must happen before pagination");
    TIME.set(from().plusMinutes(15).toInstant());
    service.sweep();
    TIME.set(Instant.parse("2026-10-12T01:50:00Z"));
    service.reserve(
        user("student"),
        new ReserveRequest(1, from().plusHours(2), from().plusHours(3)),
        null,
        "page-third");
    TIME.set(from().plusHours(2).plusMinutes(15).toInstant());
    service.sweep();
    var violationOne =
        json.readTree(
            send(student, "GET", "/api/v1/violations/mine?pageSize=1&page=1", null, null).body());
    var violationTwo =
        json.readTree(
            send(student, "GET", "/api/v1/violations/mine?pageSize=1&page=2", null, null).body());
    assertEquals(1, violationOne.size());
    assertEquals(1, violationTwo.size());
    assertNotEquals(violationOne.get(0).get("id"), violationTwo.get(0).get("id"));
    assertEquals(
        400, send(student, "GET", "/api/v1/violations/mine?pageSize=0", null, null).statusCode());
    var admin = login("admin");
    var auditOne =
        json.readTree(
            send(admin, "GET", "/api/v1/admin/audit-logs?pageSize=1&page=1", null, null).body());
    var auditTwo =
        json.readTree(
            send(admin, "GET", "/api/v1/admin/audit-logs?pageSize=1&page=2", null, null).body());
    assertEquals(1, auditOne.size());
    assertEquals(1, auditTwo.size());
    assertNotEquals(auditOne.get(0).get("id"), auditTwo.get(0).get("id"));
    assertEquals(
        400, send(admin, "GET", "/api/v1/admin/audit-logs?pageSize=201", null, null).statusCode());
    assertTrue(
        json.readTree(
                send(student, "GET", "/api/v1/reservations/mine?statusGroup=current", null, null)
                    .body())
            .isEmpty());
  }

  HttpClient login(String username) throws Exception {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var client = HttpClient.newBuilder().cookieHandler(cookies).build();
    String csrf =
        json.readTree(send(client, "GET", "/api/v1/auth/csrf", null, null).body())
            .get("token")
            .asText();
    var response =
        send(
            client,
            "POST",
            "/api/v1/auth/login",
            json.writeValueAsString(Map.of("username", username, "password", "Campus123!")),
            csrf);
    assertEquals(200, response.statusCode(), response.body());
    return client;
  }

  HttpResponse<String> send(HttpClient client, String method, String path, String body, String csrf)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path));
    if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
    if (body != null) request.header("Content-Type", "application/json");
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
