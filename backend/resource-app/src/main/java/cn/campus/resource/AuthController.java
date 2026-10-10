package cn.campus.resource;

import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/v1/auth")
class AuthController {
  private final AuthenticationManager auth;
  private final ResourceService service;
  private final Sessions sessions;
  private final HttpSessionCsrfTokenRepository csrf;

  AuthController(
      AuthenticationManager auth,
      ResourceService service,
      Sessions sessions,
      HttpSessionCsrfTokenRepository csrf) {
    this.auth = auth;
    this.service = service;
    this.sessions = sessions;
    this.csrf = csrf;
  }

  record Credentials(String username, String password) {}

  @GetMapping("/csrf")
  Map<String, String> token(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/login")
  Models.User login(
      @RequestBody Credentials credentials,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (credentials.username() == null || credentials.password() == null)
      throw ApiError.bad("请输入用户名及密码");
    try {
      Authentication authenticated =
          auth.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(
                  credentials.username(), credentials.password()));
      HttpSession session = request.getSession(true);
      sessions.remove(session.getId());
      request.changeSessionId();
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(authenticated);
      SecurityContextHolder.setContext(context);
      session.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
      csrf.saveToken(null, request, response);
      sessions.register(session);
      return service.byUsername(authenticated.getName());
    } catch (AuthenticationException e) {
      throw new ApiError(401, "INVALID_CREDENTIALS", "用户名或密码错误");
    }
  }

  @GetMapping("/me")
  Models.User me(Authentication auth) {
    return service.byUsername(auth.getName());
  }

  @PostMapping("/logout")
  Map<String, Boolean> logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      sessions.remove(session.getId());
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    return Map.of("success", true);
  }
}
