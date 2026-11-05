package cn.campus.workflow;

import static cn.campus.workflow.Model.*;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/repair-orders/{id}/review")
class RepairReviewController {
  private final RepairReviewService service;

  RepairReviewController(RepairReviewService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<String> submit(
      @RequestAttribute User user,
      @PathVariable long id,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody RepairReviewInput input) {
    var result = service.submit(user, id, input, key, requestId);
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.json());
  }

  @GetMapping
  ResponseEntity<RepairReview> get(@RequestAttribute User user, @PathVariable long id) {
    var review = service.get(user, id);
    return review == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(review);
  }
}
