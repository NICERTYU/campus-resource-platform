package cn.campus.workflow;

import static cn.campus.workflow.Model.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ApprovalPolicy {
  final int steps;
  final Duration timeout;
  private final Set<Long> first;
  private final Set<Long> last;

  ApprovalPolicy(
      @Value("${workflow.approval.steps:2}") int steps,
      @Value("${workflow.approval.timeout:PT24H}") Duration timeout,
      @Value("${workflow.approval.first-reviewer-ids:}") String first,
      @Value("${workflow.approval.final-reviewer-ids:}") String last) {
    if ((steps != 1 && steps != 2) || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Approval requires 1/2 steps and a positive timeout");
    this.steps = steps;
    this.timeout = timeout;
    this.first = ids(first);
    this.last = ids(last);
  }

  private static Set<Long> ids(String value) {
    var ids =
        Arrays.stream(value.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .map(Long::parseLong)
            .collect(Collectors.toUnmodifiableSet());
    if (ids.stream().anyMatch(id -> id <= 0))
      throw new IllegalArgumentException("Invalid reviewer ID");
    return ids;
  }

  boolean allowed(User user, int step, int total, String department) {
    if (!user.has("ADMIN")) return false;
    Set<Long> configured = step == 1 && total == 2 ? first : last;
    return (configured.isEmpty() || configured.contains(user.id()))
        && (step != 1
            || total == 1
            || department == null
            || department.isBlank()
            || department.equals(user.department()));
  }

  ApprovalPolicyView view(User user) {
    List<Integer> reviewSteps =
        java.util.stream.IntStream.rangeClosed(1, steps)
            .filter(step -> allowed(user, step, steps, null))
            .boxed()
            .toList();
    return new ApprovalPolicyView(
        steps, timeout.toString(), reviewSteps, allowed(user, 1, 1, null));
  }

  OffsetDateTime deadline(OffsetDateTime now, OffsetDateTime start) {
    var limit = now.plus(timeout);
    return limit.isBefore(start) ? limit : start;
  }
}
