package cn.campus.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class TimeRulesTest {
  private final Instant start = Instant.parse("2026-10-12T02:00:00Z");

  @Test
  void halfOpenIntervalsPermitAdjacentBookings() {
    assertFalse(
        TimeRules.overlaps(
            start, start.plusSeconds(3600), start.plusSeconds(3600), start.plusSeconds(7200)));
    assertTrue(
        TimeRules.overlaps(
            start, start.plusSeconds(3600), start.plusSeconds(3599), start.plusSeconds(7200)));
  }

  @Test
  void checkInWindowHasInclusiveStartAndExclusiveDeadline() {
    assertFalse(TimeRules.canCheckIn(start, start.minusSeconds(901)));
    assertTrue(TimeRules.canCheckIn(start, start.minusSeconds(900)));
    assertTrue(TimeRules.canCheckIn(start, start.plusSeconds(899)));
    assertFalse(TimeRules.canCheckIn(start, start.plusSeconds(900)));
  }

  @Test
  void hourGranularityUsesCampusTimeAndMaximumDuration() {
    var from = start.atOffset(ZoneOffset.UTC);
    TimeRules.reservation(from, from.plusHours(4), 4, start.minusSeconds(1));
    assertThrows(
        ApiError.class,
        () ->
            TimeRules.reservation(
                from.plusMinutes(1), from.plusHours(1), 4, start.minusSeconds(1)));
    assertThrows(
        ApiError.class,
        () -> TimeRules.reservation(from, from.plusHours(5), 4, start.minusSeconds(1)));
    assertThrows(
        ApiError.class,
        () -> TimeRules.reservation(from, from.plusHours(1), 4, start.plusSeconds(1)));
    assertThrows(
        ApiError.class,
        () -> TimeRules.reservation(from.plusDays(31), from.plusDays(31).plusHours(1), 4, start));
  }

  @Test
  void openingUsesShanghaiDateAndTime() {
    var room =
        new Models.Classroom(
            1, "Room", "A", 1, null, LocalTime.of(8, 0), LocalTime.of(22, 0), true, 24);
    TimeRules.opening(
        room,
        OffsetDateTime.parse("2026-10-12T00:00:00Z"),
        OffsetDateTime.parse("2026-10-12T14:00:00Z"));
    assertThrows(
        ApiError.class,
        () ->
            TimeRules.opening(
                room,
                OffsetDateTime.parse("2026-10-12T23:00:00+08:00"),
                OffsetDateTime.parse("2026-10-13T09:00:00+08:00")));
  }
}
