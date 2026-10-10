package cn.campus.workflow;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
class WebConfiguration {
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    // Browser Session and CSRF belong to the resource app; IdentityFilter verifies both remotely on
    // every API request.
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}

@Component
class IdentityFilter extends OncePerRequestFilter {
  private final ResourceClient resource;
  private final ObjectMapper json;

  IdentityFilter(ResourceClient resource, ObjectMapper json) {
    this.resource = resource;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Request-ID");
    String requestId =
        supplied != null && supplied.matches("[A-Za-z0-9._-]{1,64}")
            ? supplied
            : UUID.randomUUID().toString();
    request.setAttribute("requestId", requestId);
    response.setHeader("X-Request-ID", requestId);
    if (request.getRequestURI().startsWith("/api/v1/")) {
      try {
        boolean write = !(request.getMethod().equals("GET") || request.getMethod().equals("HEAD"));
        request.setAttribute(
            "user",
            resource.identity(
                request.getHeader("Cookie"), request.getHeader("X-CSRF-TOKEN"), write, requestId));
      } catch (ApiException e) {
        response.setStatus(e.status);
        response.setContentType("application/json;charset=UTF-8");
        response
            .getWriter()
            .write(json.writeValueAsString(new Model.Error(e.code, e.getMessage(), requestId)));
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
