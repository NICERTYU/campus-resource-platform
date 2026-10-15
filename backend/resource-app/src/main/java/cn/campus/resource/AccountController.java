package cn.campus.resource;

import static cn.campus.resource.AccountModels.*;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class AccountController {
  private final AccountService accounts;
  private final ResourceService resources;

  AccountController(AccountService accounts, ResourceService resources) {
    this.accounts = accounts;
    this.resources = resources;
  }

  private long admin(Authentication auth) {
    var actor = resources.byUsername(auth.getName());
    resources.require(actor, "ADMIN");
    return actor.id();
  }

  @PostMapping("/auth/register")
  @ResponseStatus(HttpStatus.CREATED)
  Models.User register(@RequestBody Registration input, HttpServletRequest request) {
    return accounts.register(input, request.getHeader("Idempotency-Key"), RequestIds.of(request));
  }

  @GetMapping("/admin/accounts")
  Object list(
      Authentication auth,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String query) {
    return accounts.list(admin(auth), page, pageSize, query);
  }

  @PostMapping("/admin/accounts")
  @ResponseStatus(HttpStatus.CREATED)
  Account create(
      Authentication auth, @RequestBody CreateAccount input, HttpServletRequest request) {
    return accounts.create(
        admin(auth), input, request.getHeader("Idempotency-Key"), RequestIds.of(request));
  }

  @PutMapping("/admin/accounts/{id}")
  Account update(
      Authentication auth,
      @PathVariable long id,
      @RequestBody UpdateAccount input,
      HttpServletRequest request) {
    return accounts.update(
        admin(auth), id, input, request.getHeader("Idempotency-Key"), RequestIds.of(request));
  }

  @PostMapping("/admin/accounts/{id}/password")
  Object reset(
      Authentication auth,
      @PathVariable long id,
      @RequestBody PasswordChange input,
      HttpServletRequest request) {
    return accounts.resetPassword(
        admin(auth), id, input, request.getHeader("Idempotency-Key"), RequestIds.of(request));
  }
}
