package bot.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import bot.domain.liveness.LivenessStatus;
import bot.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JpaLivenessProbeAdapterTest extends AbstractPostgresIntegrationTest {

  @Autowired private JpaLivenessProbeAdapter adapter;

  @Test
  void readsSeededRowAndReportsReachable() {
    LivenessStatus status = adapter.probe();

    assertThat(status.reachable()).isTrue();
    assertThat(status.detail()).isEqualTo("ok");
  }
}
