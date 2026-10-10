package cn.campus.resource;

import static cn.campus.resource.Models.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
class InternalController {
  private final ModuleKey key;
  private final ResourceService service;
  private final Sessions sessions;

  InternalController(ModuleKey key, ResourceService service, Sessions sessions) {
    this.key = key;
    this.service = service;
    this.sessions = sessions;
  }

  private void verify(HttpServletRequest request) {
    key.check(request.getHeader("X-Module-Key"));
  }

  record IdentityInput(String cookie, String csrfToken, boolean write) {}

  @PostMapping("/identity")
  User identity(@RequestBody IdentityInput input, HttpServletRequest request) {
    verify(request);
    request.getSession(false);
    return service.byUsername(sessions.username(input.cookie(), input.csrfToken(), input.write()));
  }

  @GetMapping("/classrooms/{id}")
  Classroom room(@PathVariable long id, HttpServletRequest request) {
    verify(request);
    return service.room(id);
  }

  @GetMapping("/users")
  List<User> users(HttpServletRequest request) {
    verify(request);
    return service.allUsers();
  }

  @PutMapping("/restrictions/{type}/{id}")
  RestrictionResult restriction(
      @PathVariable String type,
      @PathVariable long id,
      @RequestBody RestrictionInput input,
      HttpServletRequest request) {
    verify(request);
    return service.restriction(type, id, input);
  }
}
