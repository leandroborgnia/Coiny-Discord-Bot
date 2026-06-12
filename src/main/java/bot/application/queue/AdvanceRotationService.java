package bot.application.queue;

import bot.domain.queue.AnnouncementView;
import bot.domain.queue.CooldownPort;
import bot.domain.queue.QueueConfigPort;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.RotationPolicy;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import bot.domain.queue.UpvotePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weekly-rotation write path (US2). Under the per-guild queue lock, it applies every whole
 * 7-day period due since the last pop (downtime catch-up, FR-032): each real pop designates the top
 * game, shifts the rest up, fixes the proposer's "wait N games" cooldown, and counts that played
 * game down against every existing cooldown; an empty period designates nothing. It returns the
 * **final** current game's announcement (once) when a channel is configured — never a
 * per-missed-week backlog.
 *
 * <p>Cooldown order is deliberate: for each pop, {@code decrementAll} runs **before** {@code set}
 * so the just-popped proposer's fresh N is not decremented by their own pop (only by subsequent
 * ones) — giving "eligible after exactly N further games played" (SC-005). Coins are untouched:
 * playing a game neither charges nor refunds.
 */
@Service
public class AdvanceRotationService {

  private final QueuePort queuePort;
  private final RotationStatePort rotationPort;
  private final CooldownPort cooldownPort;
  private final QueueConfigPort configPort;
  private final UpvotePort upvotePort;

  public AdvanceRotationService(
      QueuePort queuePort,
      RotationStatePort rotationPort,
      CooldownPort cooldownPort,
      QueueConfigPort configPort,
      UpvotePort upvotePort) {
    this.queuePort = queuePort;
    this.rotationPort = rotationPort;
    this.cooldownPort = cooldownPort;
    this.configPort = configPort;
    this.upvotePort = upvotePort;
  }

  @Transactional
  public AdvanceResult advanceDue(long guildId, Instant now) {
    queuePort.lockQueue(guildId);
    RotationState state = rotationPort.get(guildId);
    if (state.lastPopAt() == null) {
      return new AdvanceResult(0, Optional.empty()); // never bootstrapped — nothing to rotate yet
    }
    int periods = RotationPolicy.advancesDue(state.lastPopAt(), now);
    if (periods == 0) {
      return new AdvanceResult(0, Optional.empty());
    }

    int week = state.currentWeekNumber();
    Instant lastPop = state.lastPopAt();
    QueueSlot lastDesignated = null;

    for (int i = 0; i < periods; i++) {
      week++;
      lastPop = RotationPolicy.nextPopAt(lastPop, 1); // += 7 days, once per applied period
      Optional<QueueSlot> top = queuePort.top(guildId);
      if (top.isPresent()) {
        QueueSlot slot = top.get();
        int n = queuePort.otherQueuedCount(guildId, slot.id()); // others still waiting at the pop
        queuePort.markPlayed(slot.id(), week);
        queuePort.shiftUp(guildId);
        rotationPort.recordDesignation(guildId, week, slot.id(), slot.identity(), lastPop);
        cooldownPort.decrementAll(guildId); // this played game counts down existing cooldowns first
        cooldownPort.set(guildId, slot.proposerMemberId(), n); // then fix the proposer's fresh N
        rotationPort.advanceClock(guildId, slot.id(), week, lastPop);
        lastDesignated = slot;
      } else {
        rotationPort.recordDesignation(guildId, week, null, null, lastPop); // empty week
        rotationPort.advanceClock(guildId, null, week, lastPop);
        lastDesignated = null;
      }
    }

    if (lastDesignated != null && configPort.get(guildId).hasAnnouncementChannel()) {
      return new AdvanceResult(periods, Optional.of(buildAnnouncement(guildId, lastDesignated)));
    }
    return new AdvanceResult(periods, Optional.empty());
  }

  private AnnouncementView buildAnnouncement(long guildId, QueueSlot current) {
    List<AnnouncementView.UpNext> upNext =
        queuePort.queued(guildId).stream()
            .limit(5)
            .map(
                slot ->
                    new AnnouncementView.UpNext(
                        slot.game().name(),
                        upvotePort.count(slot.id(), slot.gameInstanceId()),
                        slot.game().largeImageUrl()))
            .toList();
    return new AnnouncementView(
        guildId,
        current.game().name(),
        current.proposerMemberId(),
        current.game().largeImageUrl(),
        upNext);
  }
}
