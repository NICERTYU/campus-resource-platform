package cn.campus.resource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TimeoutScanner {
  private final ResourceService service;

  TimeoutScanner(ResourceService service) {
    this.service = service;
  }

  @org.springframework.core.annotation.Order(0)
  @EventListener(ApplicationReadyEvent.class)
  void recovery() {
    service.sweep();
  }

  @Scheduled(
      fixedDelayString = "${campus.timeout-scan-ms:30000}",
      initialDelayString = "${campus.timeout-scan-ms:30000}")
  void scan() {
    service.sweep();
  }
}
