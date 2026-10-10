package cn.campus.workflow;

public class ApiException extends RuntimeException {
  final int status;
  final String code;

  ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  static ApiException bad(String message) {
    return new ApiException(400, "INVALID_INPUT", message);
  }

  static ApiException forbidden() {
    return new ApiException(403, "FORBIDDEN", "没有执行该操作的权限");
  }

  static ApiException conflict(String message) {
    return new ApiException(409, "STATE_CONFLICT", message);
  }
}
