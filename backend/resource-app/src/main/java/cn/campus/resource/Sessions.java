package cn.campus.resource;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

@Component
class Sessions implements HttpSessionListener {
  private final ConcurrentHashMap<String, HttpSession> sessions = new ConcurrentHashMap<>();
  private final Object[] accountLocks =
      java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

  <T> T withAccountLock(String username, Supplier<T> operation) {
    int index = Math.floorMod(username.toLowerCase(Locale.ROOT).hashCode(), accountLocks.length);
    synchronized (accountLocks[index]) {
      return operation.get();
    }
  }

  void register(HttpSession session) {
    sessions.put(session.getId(), session);
  }

  void remove(String id) {
    sessions.remove(id);
  }

  void invalidate(String username) {
    sessions.forEach(
        (id, session) -> {
          try {
            SecurityContext context =
                (SecurityContext)
                    session.getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (context != null
                && context.getAuthentication() != null
                && username.equalsIgnoreCase(context.getAuthentication().getName())) {
              sessions.remove(id, session);
              session.invalidate();
            }
          } catch (IllegalStateException expired) {
            sessions.remove(id, session);
          }
        });
  }

  @Override
  public void sessionDestroyed(HttpSessionEvent event) {
    remove(event.getSession().getId());
  }

  String username(String cookie, String csrf, boolean write) {
    String id = null;
    if (cookie != null)
      for (String part : cookie.split(";")) {
        String[] pair = part.trim().split("=", 2);
        if (pair.length == 2 && pair[0].equals("JSESSIONID")) id = pair[1];
      }
    HttpSession session = id == null ? null : sessions.get(id);
    try {
      if (session == null) throw unauthenticated();
      if (session.getMaxInactiveInterval() > 0
          && System.currentTimeMillis() - session.getLastAccessedTime()
              > session.getMaxInactiveInterval() * 1000L) {
        sessions.remove(id);
        throw unauthenticated();
      }
      SecurityContext context =
          (SecurityContext)
              session.getAttribute(
                  HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
      if (context == null
          || context.getAuthentication() == null
          || !context.getAuthentication().isAuthenticated()) throw unauthenticated();
      if (write) {
        CsrfToken token = (CsrfToken) session.getAttribute("CAMPUS_CSRF");
        if (token == null
            || csrf == null
            || !MessageDigest.isEqual(
                token.getToken().getBytes(StandardCharsets.UTF_8),
                csrf.getBytes(StandardCharsets.UTF_8))) throw ApiError.forbidden();
      }
      return context.getAuthentication().getName();
    } catch (IllegalStateException e) {
      if (id != null) sessions.remove(id);
      throw unauthenticated();
    }
  }

  private ApiError unauthenticated() {
    return new ApiError(401, "UNAUTHENTICATED", "登录会话已失效");
  }
}
