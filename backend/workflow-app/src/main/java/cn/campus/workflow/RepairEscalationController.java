package cn.campus.workflow;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
class RepairEscalationController {
  private final RepairEscalationService service;

  RepairEscalationController(RepairEscalationService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/repair-orders/escalations")
  List<Model.RepairEscalation> list(
      @RequestAttribute Model.User user,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize) {
    return service.list(user, page, pageSize);
  }
}
