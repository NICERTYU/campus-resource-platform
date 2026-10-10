package cn.campus.resource;

import java.time.*;

final class TimeRules {
  static final ZoneId CAMPUS = ZoneId.of("Asia/Shanghai");

  private TimeRules() {}

  static boolean overlaps(Instant start, Instant end, Instant otherStart, Instant otherEnd) {
    return start.isBefore(otherEnd) && otherStart.isBefore(end);
  }

  static void interval(OffsetDateTime start, OffsetDateTime end) {
    if (start == null || end == null || !start.isBefore(end)) throw ApiError.bad("开始时间必须早于结束时间");
  }

  static void reservation(OffsetDateTime start, OffsetDateTime end, int max, Instant now) {
    interval(start, end);
    var localStart = start.atZoneSameInstant(CAMPUS);
    var localEnd = end.atZoneSameInstant(CAMPUS);
    if (localStart.getMinute() != 0
        || localStart.getSecond() != 0
        || localStart.getNano() != 0
        || localEnd.getMinute() != 0
        || localEnd.getSecond() != 0
        || localEnd.getNano() != 0) throw ApiError.bad("预约必须按整点小时填写");
    if (start.toInstant().isBefore(now)) throw ApiError.bad("不能预约过去的时段");
    if (start.toInstant().isAfter(now.plus(Duration.ofDays(30)))) throw ApiError.bad("只能提前 30 天预约");
    if (Duration.between(start, end).compareTo(Duration.ofHours(max)) > 0)
      throw ApiError.bad("预约时长超出系统上限");
  }

  static void opening(Models.Classroom room, OffsetDateTime start, OffsetDateTime end) {
    interval(start, end);
    var from = start.atZoneSameInstant(CAMPUS);
    var to = end.atZoneSameInstant(CAMPUS);
    if (!from.toLocalDate().equals(to.toLocalDate())
        || from.toLocalTime().isBefore(room.openTime())
        || to.toLocalTime().isAfter(room.closeTime())) throw ApiError.bad("该时段不在教室开放时间内");
  }

  static boolean canCheckIn(Instant start, Instant now) {
    return !now.isBefore(start.minus(Duration.ofMinutes(15)))
        && now.isBefore(start.plus(Duration.ofMinutes(15)));
  }
}
