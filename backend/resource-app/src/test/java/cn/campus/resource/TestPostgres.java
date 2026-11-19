package cn.campus.resource;

import java.net.URI;
import java.sql.DriverManager;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Defaults to Docker; optional loopback PostgreSQL creates and drops only its own test database.
 */
final class TestPostgres extends PostgreSQLContainer {
  private final String localUrl = System.getenv("CAMPUS_TEST_PG_URL");
  private final String database = "campus_test_" + UUID.randomUUID().toString().replace("-", "");
  private String jdbcUrl;
  private boolean created;

  TestPostgres() {
    super("postgres:18.6");
  }

  @Override
  public void start() {
    if (localUrl == null) {
      super.start();
      return;
    }
    var uri = URI.create(localUrl.substring("jdbc:".length()));
    if (!"postgresql".equals(uri.getScheme())
        || !java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
        || uri.getRawQuery() != null
        || !"/postgres".equals(uri.getPath()))
      throw new IllegalArgumentException(
          "CAMPUS_TEST_PG_URL must target a loopback /postgres database without query parameters");
    jdbcUrl = localUrl.substring(0, localUrl.lastIndexOf('/') + 1) + database;
    try (var connection = DriverManager.getConnection(localUrl, getUsername(), getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
      created = true;
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  @Override
  public void stop() {
    if (localUrl == null) {
      super.stop();
      return;
    }
    if (!created) return;
    try (var connection = DriverManager.getConnection(localUrl, getUsername(), getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
      created = false;
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  @Override
  public String getJdbcUrl() {
    return localUrl == null ? super.getJdbcUrl() : jdbcUrl;
  }

  @Override
  public String getUsername() {
    return localUrl == null ? super.getUsername() : required("CAMPUS_TEST_PG_USER");
  }

  @Override
  public String getPassword() {
    return localUrl == null ? super.getPassword() : required("CAMPUS_TEST_PG_PASSWORD");
  }

  private String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
