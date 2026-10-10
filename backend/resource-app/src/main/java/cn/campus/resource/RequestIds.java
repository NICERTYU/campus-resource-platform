package cn.campus.resource;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class RequestIds extends OncePerRequestFilter {
  static String of(HttpServletRequest request) {
    Object value = request.getAttribute("campusRequestId");
    return value == null ? "unknown" : value.toString();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String id = req.getHeader("X-Request-ID");
    if (id == null || !id.matches("[A-Za-z0-9._:-]{1,100}")) id = UUID.randomUUID().toString();
    req.setAttribute("campusRequestId", id);
    res.setHeader("X-Request-ID", id);
    chain.doFilter(req, res);
  }
}
