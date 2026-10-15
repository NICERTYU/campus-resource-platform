package cn.campus.resource;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;

@Configuration
@ConditionalOnWebApplication
class SecurityConfig {
  @Bean
  org.springframework.boot.web.servlet.ServletListenerRegistrationBean<Sessions> sessionListener(
      Sessions sessions) {
    return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(sessions);
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(ResourceService service) {
    return username -> {
      var row = service.loginUser(username);
      var user = service.user(((Number) row.get("id")).longValue());
      return User.withUsername(user.username())
          .password(row.get("password_hash").toString())
          .disabled(!(Boolean) row.get("enabled"))
          .roles(user.roles().toArray(String[]::new))
          .build();
    };
  }

  @Bean
  AuthenticationManager authenticationManager(UserDetailsService details, PasswordEncoder encoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(details);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(provider);
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfRepository() {
    var repo = new HttpSessionCsrfTokenRepository();
    repo.setSessionAttributeName("CAMPUS_CSRF");
    return repo;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, HttpSessionCsrfTokenRepository csrf)
      throws Exception {
    http.csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/internal/**"))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/internal/**",
                        "/actuator/health",
                        "/actuator/info")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, error) -> write(req, res, 401, "UNAUTHENTICATED", "请先登录"))
                    .accessDeniedHandler(
                        (req, res, error) ->
                            write(req, res, 403, "FORBIDDEN", "没有权限或 CSRF Token 无效")))
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable());
    return http.build();
  }

  private static void write(
      HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response
        .getWriter()
        .write(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(ApiErrors.body(code, message, request)));
  }
}

@org.springframework.stereotype.Component
class ModuleKey {
  private final String key;

  ModuleKey(@Value("${campus.module-key}") String key) {
    if (key.length() < 32)
      throw new IllegalArgumentException("MODULE_KEY must contain at least 32 characters");
    this.key = key;
  }

  void check(String supplied) {
    if (supplied == null
        || !MessageDigest.isEqual(
            key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
      throw ApiError.forbidden();
  }
}
