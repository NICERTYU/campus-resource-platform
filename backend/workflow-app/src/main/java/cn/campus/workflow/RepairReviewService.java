package cn.campus.workflow;

import static cn.campus.workflow.Model.*;
import static cn.campus.workflow.WorkflowRepository.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
class RepairReviewService {
  private final WorkflowRepository repo;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final Clock clock;

  RepairReviewService(
      WorkflowRepository repo, TransactionTemplate tx, ObjectMapper json, Clock clock) {
    this.repo = repo;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  Result submit(User user, long id, RepairReviewInput input, String suppliedKey, String requestId) {
    int rating;
    try {
      if (input.rating() == null) throw ApiException.bad("请选择评分");
      rating = input.rating().intValueExact();
    } catch (ArithmeticException error) {
      throw ApiException.bad("评分必须为1至5的整数");
    }
    if (rating < 1 || rating > 5) throw ApiException.bad("评分必须为1至5的整数");
    String comment = Rules.text(input.comment(), 1000, false);
    String key =
        suppliedKey == null || suppliedKey.isBlank() ? UUID.randomUUID().toString() : suppliedKey;
    if (key.length() > 128) throw ApiException.bad("Idempotency-Key 最多128字符");
    return tx.execute(
        status -> {
          var row = repo.locked("M3", id);
          if (number(row, "reporter_id") != user.id()) throw ApiException.forbidden();
          if (!"ACCEPTED".equals(string(row, "status"))) throw ApiException.conflict("验收通过后才能评价");
          var existing =
              repo.jdbc().queryForList("SELECT * FROM repair_reviews WHERE repair_id=?", id);
          if (!existing.isEmpty()) {
            var review = existing.getFirst();
            if (!key.equals(string(review, "request_key")))
              throw ApiException.conflict("工单已评价，不能重复提交");
            if (number(review, "rating") != rating
                || !Objects.equals(review.get("comment"), comment))
              throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "相同请求键不能用于不同评价");
            return new Result(201, json.writeValueAsString(find(id)));
          }
          repo.jdbc()
              .update(
                  """
                  INSERT INTO repair_reviews(repair_id,reviewer_id,rating,comment,request_key,reviewed_at)
                  VALUES(?,?,?,?,?,?)
                  """,
                  id,
                  user.id(),
                  rating,
                  comment,
                  key,
                  OffsetDateTime.now(clock));
          repo.audit(user, "M3", id, "REVIEW", requestId);
          return new Result(201, json.writeValueAsString(find(id)));
        });
  }

  RepairReview get(User user, long id) {
    var rows = repo.jdbc().queryForList("SELECT reporter_id FROM repair_orders WHERE id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "工单不存在");
    if (!user.has("ADMIN") && number(rows.getFirst(), "reporter_id") != user.id())
      throw ApiException.forbidden();
    return find(id);
  }

  private RepairReview find(long id) {
    var rows =
        repo.jdbc()
            .query(
                "SELECT * FROM repair_reviews WHERE repair_id=?",
                (r, n) ->
                    new RepairReview(
                        r.getLong("repair_id"),
                        r.getLong("reviewer_id"),
                        r.getInt("rating"),
                        r.getString("comment"),
                        time(r, "reviewed_at")),
                id);
    return rows.isEmpty() ? null : rows.getFirst();
  }
}
