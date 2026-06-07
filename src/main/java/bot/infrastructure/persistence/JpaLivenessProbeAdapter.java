package bot.infrastructure.persistence;

import bot.domain.liveness.LivenessProbePort;
import bot.domain.liveness.LivenessStatus;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Non-transactional adapter that owns connectivity. A down database surfaces as a {@link
 * SQLException} from {@link DataSource#getConnection()} and is caught as a value, so it becomes a
 * clean {@link LivenessStatus#down(String)} rather than an exception that escapes to the command
 * handler. On a healthy connection it reads the seeded {@code health_check} row.
 */
@Component
public class JpaLivenessProbeAdapter implements LivenessProbePort {

  private static final short PROBE_ID = 1;
  private static final int VALIDATION_TIMEOUT_SECONDS = 2;

  private final DataSource dataSource;
  private final HealthCheckJpaRepository repository;

  public JpaLivenessProbeAdapter(DataSource dataSource, HealthCheckJpaRepository repository) {
    this.dataSource = dataSource;
    this.repository = repository;
  }

  @Override
  public LivenessStatus probe() {
    // 1) Connectivity check — a down store throws SQLException here, caught as a value.
    try (Connection connection = dataSource.getConnection()) {
      if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
        return LivenessStatus.down("data store connection is not valid");
      }
    } catch (SQLException e) {
      return LivenessStatus.down("data store unreachable");
    }

    // 2) Read the seeded row; defensively map any data-access failure to down.
    try {
      return repository
          .findById(PROBE_ID)
          .map(row -> LivenessStatus.up(row.getLabel()))
          .orElseGet(() -> LivenessStatus.down("health-check row missing"));
    } catch (DataAccessException e) {
      return LivenessStatus.down("data store read failed");
    }
  }
}
