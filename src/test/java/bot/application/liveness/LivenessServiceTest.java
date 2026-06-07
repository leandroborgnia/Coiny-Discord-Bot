package bot.application.liveness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import bot.domain.liveness.LivenessProbePort;
import bot.domain.liveness.LivenessStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LivenessServiceTest {

  @Mock private LivenessProbePort livenessProbePort;

  @InjectMocks private LivenessService livenessService;

  @Test
  void mapsUpStatusToReachableResult() {
    when(livenessProbePort.probe()).thenReturn(LivenessStatus.up("ok"));

    CheckLivenessResult result = livenessService.check(CheckLivenessRequest.instance());

    assertThat(result.reachable()).isTrue();
    assertThat(result.detail()).isEqualTo("ok");
  }

  @Test
  void mapsDownStatusToUnreachableResult() {
    when(livenessProbePort.probe()).thenReturn(LivenessStatus.down("data store unreachable"));

    CheckLivenessResult result = livenessService.check(CheckLivenessRequest.instance());

    assertThat(result.reachable()).isFalse();
    assertThat(result.detail()).isEqualTo("data store unreachable");
  }
}
