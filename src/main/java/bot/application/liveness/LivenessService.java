package bot.application.liveness;

import bot.domain.liveness.LivenessProbePort;
import bot.domain.liveness.LivenessStatus;
import org.springframework.stereotype.Service;

/**
 * Application service for the liveness use case.
 *
 * <p>Intentionally <strong>not</strong> {@code @Transactional}: a transactional proxy would acquire
 * a database connection before this method body runs, so a {@code SQLException} from a down
 * database would surface from the proxy and could never be turned into a clean {@code
 * reachable=false}. Connectivity handling therefore lives in the infrastructure adapter behind
 * {@link LivenessProbePort}; this service is pure delegation and mapping.
 */
@Service
public class LivenessService {

  private final LivenessProbePort livenessProbePort;

  public LivenessService(LivenessProbePort livenessProbePort) {
    this.livenessProbePort = livenessProbePort;
  }

  public CheckLivenessResult check(CheckLivenessRequest request) {
    LivenessStatus status = livenessProbePort.probe();
    return new CheckLivenessResult(status.reachable(), status.detail());
  }
}
