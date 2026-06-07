package bot.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests: boots the full Spring context (Discord disabled) against a real,
 * throwaway Postgres 17 on the host Docker daemon — never inside the application container.
 *
 * <p>Uses the singleton-container pattern: one container is started once per JVM and shared across
 * all test classes. This keeps the JDBC port stable so Spring's cached application context (reused
 * across test classes with identical configuration) stays valid. Testcontainers' Ryuk reaper stops
 * the container at JVM exit, so no explicit stop is needed.
 */
@SpringBootTest
public abstract class AbstractPostgresIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
