package cn.campus.resource;

import java.util.List;

final class AccountModels {
  private AccountModels() {}

  record Account(
      long id,
      String username,
      String displayName,
      String department,
      List<String> roles,
      boolean enabled) {}

  record Registration(
      String username,
      String password,
      String displayName,
      String department,
      List<String> roles) {}

  record CreateAccount(
      String username,
      String password,
      String displayName,
      String department,
      List<String> roles,
      Boolean enabled) {}

  record UpdateAccount(
      String displayName, String department, List<String> roles, Boolean enabled) {}

  record PasswordChange(String password) {}
}
