package cn.campus.workflow;

import static cn.campus.workflow.Model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class ResourceClient {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper json;
  private final String baseUrl;
  private final String moduleKey;

  ResourceClient(
      ObjectMapper json,
      @Value("${workflow.resource-url}") String baseUrl,
      @Value("${workflow.module-key}") String moduleKey) {
    if (moduleKey.length() < 32)
      throw new IllegalArgumentException("MODULE_KEY must contain at least 32 characters");
    this.json = json;
    this.baseUrl = baseUrl.replaceAll("/$", "");
    this.moduleKey = moduleKey;
  }

  User identity(String cookie, String csrf, boolean write, String requestId) {
    var body = new LinkedHashMap<String, Object>();
    body.put("cookie", cookie == null ? "" : cookie);
    body.put("csrfToken", csrf);
    body.put("write", write);
    return send("POST", "/internal/v1/identity", body, requestId, User.class, cookie);
  }

  Classroom classroom(long id, String requestId) {
    return send("GET", "/internal/v1/classrooms/" + id, null, requestId, Classroom.class);
  }

  List<User> users(String requestId) {
    return Arrays.asList(
        send("GET", "/internal/v1/users?role=TECHNICIAN", null, requestId, User[].class));
  }

  RestrictionAck restriction(Intent intent) {
    return send(
        "PUT",
        "/internal/v1/restrictions/" + intent.type() + "/" + intent.sourceId(),
        new Restriction(
            intent.version(),
            intent.active(),
            intent.classroomId(),
            intent.start(),
            intent.end(),
            intent.actorId(),
            intent.requestId()),
        intent.requestId(),
        RestrictionAck.class);
  }

  private <T> T send(String method, String path, Object body, String requestId, Class<T> type) {
    return send(method, path, body, requestId, type, null);
  }

  private <T> T send(
      String method, String path, Object body, String requestId, Class<T> type, String cookie) {
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(Duration.ofSeconds(3))
              .header("X-Module-Key", moduleKey)
              .header("X-Request-ID", requestId)
              .header("Content-Type", "application/json");
      // Associate the internal request with the real resource Session so workflow activity
      // refreshes its idle timeout.
      if (cookie != null && !cookie.isBlank()) builder.header("Cookie", cookie);
      var request =
          builder
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        String code = "RESOURCE_ERROR", message = "资源服务暂时无法处理请求";
        try {
          var error = json.readTree(response.body());
          code = error.path("code").asText(code);
          message = error.path("message").asText(message);
        } catch (RuntimeException ignored) {
          /* HTML proxy errors do not replace the stable error. */
        }
        throw new ApiException(response.statusCode(), code, message);
      }
      return json.readValue(response.body(), type);
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "RESOURCE_UNAVAILABLE", "资源服务响应中断，请稍后重试");
    } catch (Exception e) {
      throw new ApiException(503, "RESOURCE_UNAVAILABLE", "资源服务暂时不可用，请稍后重试");
    }
  }
}
