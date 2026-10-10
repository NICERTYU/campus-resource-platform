package cn.campus.resource;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ResourceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ResourceApplication.class, args);
  }

  @Bean
  @org.springframework.context.annotation.Profile("!e2e")
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @org.springframework.context.annotation.Profile("e2e")
  Clock e2eClock(
      @org.springframework.beans.factory.annotation.Value("${campus.test-clock}") String instant) {
    return Clock.fixed(java.time.Instant.parse(instant), java.time.ZoneOffset.UTC);
  }
}
