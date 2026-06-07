package bot;

import static org.assertj.core.api.Assertions.assertThat;

import bot.support.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Validates fail-fast startup + migration, and restart idempotency (spec FR-004/FR-008/FR-009). */
class StartupIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private Flyway flyway;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void migrationAppliedAndSeedRowExists() {
    MigrationInfo current = flyway.info().current();
    assertThat(current).isNotNull();
    assertThat(current.getVersion().getVersion()).isEqualTo("1");

    Integer count = jdbcTemplate.queryForObject("select count(*) from health_check", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void secondMigrateIsNoOpAndKeepsSingleSeedRow() {
    MigrateResult result = flyway.migrate();

    assertThat(result.migrationsExecuted).isZero();
    assertThat(flyway.info().pending()).isEmpty();

    Integer count = jdbcTemplate.queryForObject("select count(*) from health_check", Integer.class);
    assertThat(count).isEqualTo(1);
  }
}
