package cn.campus.resource;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class DemoSeeder implements CommandLineRunner {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean enabled;
  private final ConfigurableApplicationContext context;

  DemoSeeder(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      Clock clock,
      @Value("${campus.seed:false}") boolean enabled,
      ConfigurableApplicationContext context) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.clock = clock;
    this.enabled = enabled;
    this.context = context;
  }

  @Override
  public void run(String... args) {
    if (enabled) {
      seed();
      org.slf4j.LoggerFactory.getLogger(getClass())
          .info("Demo initialization completed without overwriting existing accounts or resources");
    }
  }

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  @org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
  void closeSeedProcess() {
    if (enabled) context.close();
  }

  void seed() {
    tx.executeWithoutResult(
        status -> {
          var bcrypt = new BCryptPasswordEncoder();
          String password = bcrypt.encode("Campus123!");
          List<List<String>> people =
              List.of(
                  List.of("student", "学生张同学", "CS", "STUDENT"),
                  List.of("student2", "学生李同学", "CS", "STUDENT"),
                  List.of("outsider", "艺术学院学生", "ART", "STUDENT"),
                  List.of("applicant", "活动申请人", "CS", "APPLICANT"),
                  List.of("admin", "资源管理员", "CS", "ADMIN", "STUDENT", "APPLICANT"),
                  List.of("technician", "维修工程师", "CS", "TECHNICIAN"));
          for (var person : people) {
            db.update(
                "INSERT INTO users(username,password_hash,display_name,department) VALUES(?,?,?,?)"
                    + " ON CONFLICT(username) DO NOTHING",
                person.get(0),
                password,
                person.get(1),
                person.get(2));
            long id =
                db.queryForObject(
                    "SELECT id FROM users WHERE username=?", Long.class, person.getFirst());
            for (int i = 3; i < person.size(); i++)
              db.update(
                  "INSERT INTO user_roles(user_id,role) VALUES(?,?) ON CONFLICT DO NOTHING",
                  id,
                  person.get(i));
          }
          for (int id = 1; id <= 3; id++) {
            String name = id == 1 ? "图书馆自习室" : id == 2 ? "计算机学院自习室" : "艺术学院自习室";
            db.update(
                "INSERT INTO"
                    + " classrooms(id,name,building,floor,department,open_time,close_time,enabled)"
                    + " VALUES(?,?,?,?,?,'08:00','22:00',true) ON CONFLICT DO NOTHING",
                id,
                name,
                id == 1 ? "图书馆" : "教学楼",
                id,
                id == 1 ? null : id == 2 ? "CS" : "ART");
            for (int number = 1; number <= 24; number++)
              db.update(
                  "INSERT INTO seats(classroom_id,label,has_power,enabled) VALUES(?,?,?,true) ON"
                      + " CONFLICT(classroom_id,label) DO NOTHING",
                  id,
                  String.format("%02d", number),
                  number % 2 == 0);
            db.update(
                "INSERT INTO check_in_codes(classroom_id,campus_date,code_hash) VALUES(?,?,?) ON"
                    + " CONFLICT DO NOTHING",
                id,
                clock.instant().atZone(TimeRules.CAMPUS).toLocalDate(),
                bcrypt.encode("246810"));
          }
          db.execute(
              "SELECT setval(pg_get_serial_sequence('classrooms','id'),GREATEST((SELECT max(id)"
                  + " FROM classrooms),1))");
        });
  }
}
