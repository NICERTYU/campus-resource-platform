package cn.campus.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReservationStatisticsTest {
  ReservationStatistics.Span span(String start, String end) {
    return new ReservationStatistics.Span(
        Instant.parse("2026-11-06T" + start + ":00Z"), Instant.parse("2026-11-06T" + end + ":00Z"));
  }

  @Test
  void overlappingAndAdjacentMaintenanceSourcesAreCountedOnce() {
    var union =
        ReservationStatistics.merge(
            List.of(
                span("10:00", "12:00"),
                span("09:00", "11:00"),
                span("12:00", "13:00"),
                span("15:00", "16:00")));
    assertEquals(List.of(span("09:00", "13:00"), span("15:00", "16:00")), union);
    assertEquals(5, ReservationStatistics.hours(union, List.of(span("08:00", "22:00"))));
  }

  @Test
  void hoursAreClippedToOpeningsAndIgnoreEmptyIntervals() {
    assertEquals(
        1,
        ReservationStatistics.hours(
            List.of(span("07:00", "09:00")), List.of(span("08:00", "22:00"))));
    assertTrue(ReservationStatistics.merge(List.of(span("10:00", "10:00"))).isEmpty());
    assertEquals(
        0,
        ReservationStatistics.hours(
            List.of(span("06:00", "08:00")), List.of(span("08:00", "22:00"))));
  }
}
