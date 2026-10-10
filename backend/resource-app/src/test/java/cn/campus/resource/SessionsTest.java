package cn.campus.resource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class SessionsTest {
  private HttpSession authenticatedSession(String id) {
    var session = mock(HttpSession.class);
    when(session.getId()).thenReturn(id);
    when(session.getMaxInactiveInterval()).thenReturn(3600);
    when(session.getLastAccessedTime()).thenReturn(System.currentTimeMillis());
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("student", null, List.of()));
    when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
        .thenReturn(context);
    return session;
  }

  @Test
  void destroyedContainerSessionCannotBeUsedThroughInternalIdentity() {
    var registry = new Sessions();
    var session = authenticatedSession("destroyed");
    registry.register(session);
    assertEquals("student", registry.username("JSESSIONID=destroyed", null, false));
    registry.sessionDestroyed(new HttpSessionEvent(session));
    assertEquals(
        401,
        assertThrows(ApiError.class, () -> registry.username("JSESSIONID=destroyed", null, false))
            .status);
  }

  @Test
  void expiredSessionIsRemovedAndCannotBeRevivedByRetainedObject() {
    var registry = new Sessions();
    var session = authenticatedSession("expired");
    registry.register(session);
    when(session.getMaxInactiveInterval()).thenReturn(1);
    when(session.getLastAccessedTime()).thenReturn(System.currentTimeMillis() - 2000);
    assertEquals(
        401,
        assertThrows(ApiError.class, () -> registry.username("JSESSIONID=expired", null, false))
            .status);
    when(session.getLastAccessedTime()).thenReturn(System.currentTimeMillis());
    assertEquals(
        401,
        assertThrows(ApiError.class, () -> registry.username("JSESSIONID=expired", null, false))
            .status);
  }

  @Test
  void internalWriteRequiresCsrfTokenBoundToThatSession() {
    var registry = new Sessions();
    var session = authenticatedSession("csrf-session");
    registry.register(session);
    when(session.getAttribute("CAMPUS_CSRF"))
        .thenReturn(new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "session-bound-token"));
    assertEquals(
        403,
        assertThrows(
                ApiError.class,
                () -> registry.username("JSESSIONID=csrf-session", "different-token", true))
            .status);
    assertEquals(
        "student", registry.username("JSESSIONID=csrf-session", "session-bound-token", true));
  }
}
