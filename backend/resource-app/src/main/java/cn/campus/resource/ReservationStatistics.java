package cn.campus.resource;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/reservation-statistics")
class ReservationStatistics {
  private final ResourceService service;
  private final JdbcTemplate db;

  ReservationStatistics(ResourceService service, JdbcTemplate db) {
    this.service = service;
    this.db = db;
  }

  record Span(Instant start, Instant end) {}

  record Row(
      long classroomId,
      String classroomName,
      long reservationCount,
      long cancelledCount,
      long noShowCount,
      long maintenanceCancelledCount,
      double bookedSeatHours,
      double capacitySeatHours,
      double maintenanceSeatHours,
      Double utilization) {}

  record Report(LocalDate startDate, LocalDate endDate, List<Row> rows) {}

  @GetMapping
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Report report(
      Authentication auth,
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate,
      @RequestParam(required = false) Long classroomId) {
    service.require(service.byUsername(auth.getName()), "ADMIN");
    if (startDate == null
        || endDate == null
        || endDate.isBefore(startDate)
        || ChronoUnit.DAYS.between(startDate, endDate) >= 31)
      throw ApiError.bad("请选择首尾有效且不超过31天的统计日期");
    if (classroomId != null) service.room(classroomId);
    Instant from = startDate.atStartOfDay(TimeRules.CAMPUS).toInstant();
    Instant to = endDate.plusDays(1).atStartOfDay(TimeRules.CAMPUS).toInstant();
    Instant now = service.now();
    var rooms =
        db.queryForList(
            "SELECT c.*,(SELECT count(*) FROM seats s WHERE s.classroom_id=c.id AND s.enabled)"
                + " capacity FROM classrooms c WHERE (?::bigint IS NULL OR c.id=?) ORDER BY c.id",
            classroomId,
            classroomId);
    var rows = new ArrayList<Row>();
    for (var room : rooms) {
      long id = ((Number) room.get("id")).longValue();
      long capacity =
          Boolean.TRUE.equals(room.get("enabled"))
              ? ((Number) room.get("capacity")).longValue()
              : 0;
      LocalTime open = ((java.sql.Time) room.get("open_time")).toLocalTime();
      LocalTime close = ((java.sql.Time) room.get("close_time")).toLocalTime();
      List<Span> openings = new ArrayList<>();
      for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1))
        openings.add(
            new Span(
                day.atTime(open).atZone(TimeRules.CAMPUS).toInstant(),
                day.atTime(close).atZone(TimeRules.CAMPUS).toInstant()));
      var counts =
          db.queryForMap(
              "SELECT count(*) total,count(*) FILTER(WHERE r.status='CANCELLED') cancelled,"
                  + " count(*) FILTER(WHERE r.status='NO_SHOW') no_show,"
                  + " count(*) FILTER(WHERE r.cancel_reason='MAINTENANCE') maintenance"
                  + " FROM reservations r JOIN seats s ON s.id=r.seat_id"
                  + " WHERE s.classroom_id=? AND r.start_time>=? AND r.start_time<?",
              id,
              Timestamp.from(from),
              Timestamp.from(to));
      var booked =
          db.query(
              "SELECT r.start_time,r.end_time FROM reservations r JOIN seats s ON s.id=r.seat_id"
                  + " WHERE s.classroom_id=? AND s.enabled AND r.start_time<? AND r.end_time>?"
                  + " AND (r.status IN('CHECKED_IN','COMPLETED') OR (r.status='PENDING'"
                  + " AND r.check_in_deadline>?))",
              (rs, n) -> new Span(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()),
              id,
              Timestamp.from(to),
              Timestamp.from(from),
              Timestamp.from(now));
      var maintenance =
          db.query(
              "SELECT start_time,LEAST(COALESCE(released_at,?),?) finish FROM restrictions"
                  + " WHERE classroom_id=? AND source_type='M3' AND start_time<?"
                  + " AND COALESCE(released_at,?)>? ORDER BY start_time",
              (rs, n) -> new Span(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()),
              Timestamp.from(now),
              Timestamp.from(now),
              id,
              Timestamp.from(to),
              Timestamp.from(now),
              Timestamp.from(from));
      double capacityHours = hours(openings, openings) * capacity;
      double bookedHours = capacity == 0 ? 0 : hours(booked, openings);
      double maintenanceHours = hours(merge(maintenance), openings) * capacity;
      rows.add(
          new Row(
              id,
              room.get("name").toString(),
              number(counts, "total"),
              number(counts, "cancelled"),
              number(counts, "no_show"),
              number(counts, "maintenance"),
              bookedHours,
              capacityHours,
              maintenanceHours,
              capacityHours == 0 ? null : bookedHours / capacityHours));
    }
    return new Report(startDate, endDate, rows);
  }

  private static long number(Map<String, Object> values, String name) {
    return ((Number) values.get(name)).longValue();
  }

  static List<Span> merge(List<Span> spans) {
    var sorted =
        spans.stream()
            .filter(s -> s.end().isAfter(s.start()))
            .sorted(Comparator.comparing(Span::start))
            .toList();
    var result = new ArrayList<Span>();
    for (var span : sorted) {
      if (result.isEmpty() || result.getLast().end().isBefore(span.start())) result.add(span);
      else {
        Span previous = result.removeLast();
        result.add(
            new Span(
                previous.start(),
                previous.end().isAfter(span.end()) ? previous.end() : span.end()));
      }
    }
    return result;
  }

  static double hours(List<Span> spans, List<Span> openings) {
    double seconds = 0;
    for (var span : spans)
      for (var opening : openings) {
        Instant start = span.start().isAfter(opening.start()) ? span.start() : opening.start();
        Instant end = span.end().isBefore(opening.end()) ? span.end() : opening.end();
        if (end.isAfter(start)) seconds += Duration.between(start, end).toMillis() / 1000.0;
      }
    return seconds / 3600.0;
  }
}
