package cn.campus.workflow;

import static cn.campus.workflow.Model.*;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

final class Rules {
  static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

  private Rules() {}

  static long pageOffset(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 200)
      throw ApiException.bad("page 必须大于等于1，pageSize 必须在1至200之间");
    return (long) (page - 1) * pageSize;
  }

  static void classroomAccess(User user, Classroom room) {
    if (!room.enabled()) throw ApiException.conflict("教室当前未开放");
    if (!user.has("ADMIN")
        && room.department() != null
        && !room.department().isBlank()
        && !room.department().equals(user.department())) throw ApiException.forbidden();
  }

  static String text(String value, int max, boolean required) {
    if (value == null) {
      if (required) throw ApiException.bad("内容不能为空");
      return null;
    }
    String clean = value.strip();
    if ((required && clean.isEmpty()) || clean.length() > max)
      throw ApiException.bad("内容为空或超过长度限制");
    return clean;
  }

  static void applicationTime(
      OffsetDateTime start, OffsetDateTime end, Classroom room, Clock clock) {
    if (start == null
        || end == null
        || !end.isAfter(start)
        || !start.toInstant().isAfter(clock.instant())) throw ApiException.bad("请选择有效的未来起止时间");
    if (start.getSecond() != 0
        || end.getSecond() != 0
        || start.getNano() != 0
        || end.getNano() != 0) throw ApiException.bad("教室申请时间精确到分钟");
    var localStart = start.atZoneSameInstant(CAMPUS_ZONE);
    var localEnd = end.atZoneSameInstant(CAMPUS_ZONE);
    if (!localStart.toLocalDate().equals(localEnd.toLocalDate())
        || localStart.toLocalTime().isBefore(LocalTime.parse(room.openTime()))
        || localEnd.toLocalTime().isAfter(LocalTime.parse(room.closeTime())))
      throw ApiException.bad("申请必须在同一天的教室开放时间内");
  }

  static void admin(User user) {
    if (!user.has("ADMIN")) throw ApiException.forbidden();
  }
}
