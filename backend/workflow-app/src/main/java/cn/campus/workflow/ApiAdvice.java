package cn.campus.workflow;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiAdvice {
  private static final Logger LOG = LoggerFactory.getLogger(ApiAdvice.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Model.Error> business(ApiException e, HttpServletRequest request) {
    return ResponseEntity.status(e.status)
        .body(new Model.Error(e.code, e.getMessage(), (String) request.getAttribute("requestId")));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Model.Error> invalid(Exception e, HttpServletRequest request) {
    return business(ApiException.bad("请求格式或参数不正确"), request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Model.Error> unexpected(Exception e, HttpServletRequest request) {
    LOG.error("Workflow failure requestId={}", request.getAttribute("requestId"), e);
    return business(new ApiException(500, "INTERNAL_ERROR", "服务暂时无法完成操作"), request);
  }
}
