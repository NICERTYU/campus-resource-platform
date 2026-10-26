package cn.campus.workflow;

import static cn.campus.workflow.Model.*;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class WorkflowController {
  private final WorkflowService service;

  WorkflowController(WorkflowService service) {
    this.service = service;
  }

  @GetMapping("/classroom-applications")
  List<ApplicationView> applications(
      @RequestAttribute User user,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize) {
    return service.applications(user, page, pageSize);
  }

  @GetMapping("/repair-orders")
  List<RepairView> repairs(
      @RequestAttribute User user,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize) {
    return service.repairs(user, page, pageSize);
  }

  @GetMapping("/classroom-applications/policy")
  ApprovalPolicyView approvalPolicy(@RequestAttribute User user) {
    return service.approvalPolicy(user);
  }

  @GetMapping("/classroom-applications/{id}/history")
  List<ApprovalHistory> history(@RequestAttribute User user, @PathVariable long id) {
    return service.applicationHistory(user, id);
  }

  @PostMapping("/classroom-applications")
  ResponseEntity<String> createApplication(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody ApplicationInput input) {
    return response(service.createApplication(user, key, input, requestId));
  }

  @PostMapping("/classroom-applications/{id}/approve")
  ResponseEntity<String> approve(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody(required = false) ReviewInput input) {
    return response(service.applicationAction(user, id, "approve", key, input, requestId));
  }

  @PostMapping("/classroom-applications/{id}/reject")
  ResponseEntity<String> reject(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody(required = false) ReviewInput input) {
    return response(service.applicationAction(user, id, "reject", key, input, requestId));
  }

  @PostMapping("/classroom-applications/{id}/withdraw")
  ResponseEntity<String> withdraw(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id) {
    return response(service.applicationAction(user, id, "withdraw", key, null, requestId));
  }

  @PostMapping("/classroom-applications/{id}/return")
  ResponseEntity<String> returnApplication(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody ReviewInput input) {
    return response(service.applicationAction(user, id, "return", key, input, requestId));
  }

  @PostMapping("/classroom-applications/{id}/resubmit")
  ResponseEntity<String> resubmit(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody ResubmitInput input) {
    return response(service.resubmit(user, id, key, input, requestId));
  }

  @PostMapping("/repair-orders")
  ResponseEntity<String> createRepair(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody RepairInput input) {
    return response(service.createRepair(user, key, input, requestId));
  }

  @PostMapping("/repair-orders/{id}/assign")
  ResponseEntity<String> assign(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody AssignmentInput input) {
    return response(service.assign(user, id, key, input, requestId));
  }

  @PostMapping("/repair-orders/{id}/start")
  ResponseEntity<String> start(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id) {
    return response(service.repairAction(user, id, "start", key, null, requestId));
  }

  @PostMapping("/repair-orders/{id}/complete")
  ResponseEntity<String> complete(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody CompletionInput input) {
    return response(service.repairAction(user, id, "complete", key, input, requestId));
  }

  @PostMapping("/repair-orders/{id}/accept")
  ResponseEntity<String> accept(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody(required = false) CommentInput input) {
    return response(service.repairAction(user, id, "accept", key, input, requestId));
  }

  @PostMapping("/repair-orders/{id}/return")
  ResponseEntity<String> returnOrder(
      @RequestAttribute User user,
      @RequestAttribute String requestId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody(required = false) CommentInput input) {
    return response(service.repairAction(user, id, "return", key, input, requestId));
  }

  private static ResponseEntity<String> response(Result result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.json());
  }
}
