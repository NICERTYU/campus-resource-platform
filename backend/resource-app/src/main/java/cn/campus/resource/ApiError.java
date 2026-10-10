package cn.campus.resource;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class ApiError extends RuntimeException {
  final int status;
  final String code;

  ApiError(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  static ApiError bad(String message) {
    return new ApiError(400, "INVALID_INPUT", message);
  }

  static ApiError conflict(String message) {
    return new ApiError(409, "RESOURCE_CONFLICT", message);
  }

  static ApiError forbidden() {
    return new ApiError(403, "FORBIDDEN", "没有权限执行此操作");
  }

  static ApiError missing() {
    return new ApiError(404, "NOT_FOUND", "记录不存在");
  }
}

@RestControllerAdvice
class ApiErrors {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> business(ApiError error, HttpServletRequest request) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .warn("Request {} rejected with {}", RequestIds.of(request), error.code);
    return ResponseEntity.status(error.status).body(body(error.code, error.getMessage(), request));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> duplicate(DataIntegrityViolationException error, HttpServletRequest request) {
    return ResponseEntity.status(409).body(body("RESOURCE_CONFLICT", "数据冲突，请刷新后重试", request));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<?> invalid(Exception error, HttpServletRequest request) {
    return ResponseEntity.badRequest().body(body("INVALID_INPUT", "请求参数不正确", request));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> absent(Exception error, HttpServletRequest request) {
    return ResponseEntity.status(404).body(body("NOT_FOUND", "接口不存在", request));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception error, HttpServletRequest request) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("Request {} failed", RequestIds.of(request), error);
    return ResponseEntity.status(500).body(body("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", request));
  }

  static Map<String, String> body(String code, String message, HttpServletRequest request) {
    return Map.of("code", code, "message", message, "requestId", RequestIds.of(request));
  }
}
