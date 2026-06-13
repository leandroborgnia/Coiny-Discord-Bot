package bot.application.skipjar;

import bot.application.skipjar.SkipJarStatus.State;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import bot.domain.skipjar.EarnerStatsPort;
import bot.domain.skipjar.GuildSkipJarConfig;
import bot.domain.skipjar.SkipContributionPort;
import bot.domain.skipjar.SkipJarConfigPort;
import bot.domain.skipjar.SkipThresholdPolicy;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The skip-jar status read path (US3). Lock-free: it reads the rotation clock and reports NO_GAME
 * (no current slot), NOT_OPEN (dwell not yet elapsed, with when it opens), or OPEN (count /
 * threshold / remaining / earner set). Never throws for the no-game case (FR-014).
 */
@Service
public class ViewSkipJarService {

  private final QueuePort queuePort;
  private final RotationStatePort rotationStatePort;
  private final SkipJarConfigPort skipJarConfigPort;
  private final EarnerStatsPort earnerStatsPort;
  private final SkipContributionPort skipContributionPort;

  public ViewSkipJarService(
      QueuePort queuePort,
      RotationStatePort rotationStatePort,
      SkipJarConfigPort skipJarConfigPort,
      EarnerStatsPort earnerStatsPort,
      SkipContributionPort skipContributionPort) {
    this.queuePort = queuePort;
    this.rotationStatePort = rotationStatePort;
    this.skipJarConfigPort = skipJarConfigPort;
    this.earnerStatsPort = earnerStatsPort;
    this.skipContributionPort = skipContributionPort;
  }

  @Transactional(readOnly = true)
  public SkipJarStatus view(ViewRequest request) {
    long guildId = request.guildId();
    RotationState state = rotationStatePort.get(guildId);
    if (state.currentSlot().isEmpty()) {
      return new SkipJarStatus(State.NO_GAME, null, 0, 0, 0, 0, 0, null);
    }

    String gameName =
        state
            .currentSlot()
            .flatMap(queuePort::findSlot)
            .map(QueueSlot::game)
            .map(g -> g.name())
            .orElse(null);
    Instant becameCurrent = state.lastPopAt();
    GuildSkipJarConfig cfg = skipJarConfigPort.get(guildId);
    Instant opensAt = becameCurrent.plus(cfg.dwell());

    if (request.now().isBefore(opensAt)) {
      return new SkipJarStatus(State.NOT_OPEN, gameName, 0, 0, 0, 0, cfg.thresholdFloor(), opensAt);
    }

    int count = skipContributionPort.count(guildId, state.currentWeekNumber());
    int earnerCount = earnerStatsPort.distinctEarnerCount(guildId, becameCurrent);
    int threshold = SkipThresholdPolicy.threshold(earnerCount, cfg.thresholdFloor());
    int remaining = Math.max(0, threshold - count);
    return new SkipJarStatus(
        State.OPEN, gameName, count, threshold, remaining, earnerCount, cfg.thresholdFloor(), null);
  }
}
