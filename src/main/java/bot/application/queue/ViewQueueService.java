package bot.application.queue;

import bot.domain.queue.CooldownPolicy;
import bot.domain.queue.CooldownPort;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.QueueView;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import bot.domain.queue.UpvotePort;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the ephemeral queue view (US3): the current designated game, the next five queued
 * slots, the viewer's own queued slot (always included so it can be marked even beyond the top
 * five), and the viewer's eligibility. Each shown slot gets its snapshot upvote count and a cover
 * url resolved through the art chain.
 *
 * <p>Read-mostly, but **not** {@code readOnly}: the art chain caches IGDB results (a write to
 * {@code game_art_cache}), which a read-only transaction would reject.
 */
@Service
public class ViewQueueService {

  private final QueuePort queuePort;
  private final RotationStatePort rotationPort;
  private final CooldownPort cooldownPort;
  private final UpvotePort upvotePort;
  private final ArtResolutionChain artChain;

  public ViewQueueService(
      QueuePort queuePort,
      RotationStatePort rotationPort,
      CooldownPort cooldownPort,
      UpvotePort upvotePort,
      ArtResolutionChain artChain) {
    this.queuePort = queuePort;
    this.rotationPort = rotationPort;
    this.cooldownPort = cooldownPort;
    this.upvotePort = upvotePort;
    this.artChain = artChain;
  }

  @Transactional
  public QueueView view(ViewQueueRequest request) {
    long guildId = request.guildId();
    long memberId = request.memberId();

    List<QueueSlot> queued = queuePort.queued(guildId);
    RotationState rotation = rotationPort.get(guildId);
    Optional<QueueSlot> currentSlot = rotation.currentSlot().flatMap(queuePort::findSlot);
    Optional<QueueSlot> own = queuePort.ownQueued(guildId, memberId);
    int gamesRemaining = cooldownPort.gamesRemaining(guildId, memberId);
    boolean eligible = CooldownPolicy.eligible(own.isPresent(), gamesRemaining);

    List<QueueSlot> nextFive = queued.stream().limit(5).toList();
    boolean ownInNextFive =
        own.isPresent() && nextFive.stream().anyMatch(s -> s.id() == own.get().id());

    return new QueueView(
        currentSlot.map(this::toEntry),
        nextFive.stream().map(this::toEntry).toList(),
        own.map(this::toEntry),
        ownInNextFive,
        eligible,
        gamesRemaining);
  }

  private QueueView.Entry toEntry(QueueSlot slot) {
    int count = upvotePort.count(slot.id(), slot.gameInstanceId());
    String cover =
        artChain
            .coverFor(slot.identity(), slot.game().largeImageUrl(), slot.game().name())
            .orElse(null);
    return new QueueView.Entry(
        slot.id(),
        slot.gameInstanceId(),
        slot.proposerMemberId(),
        slot.position(),
        slot.game().name(),
        count,
        cover);
  }
}
