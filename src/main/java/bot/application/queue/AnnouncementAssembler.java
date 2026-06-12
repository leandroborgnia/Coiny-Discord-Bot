package bot.application.queue;

import bot.domain.queue.AnnouncementView;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import bot.domain.queue.UpvotePort;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Builds the live announcement view for a guild's current designated game + "up next" preview
 * (FR-036/FR-038): current game (name, proposer, resolved key art) and the next five queued games
 * (name, current upvote count, thumbnail). Shared by the weekly advance (post) and the upvote path
 * (edit), so both surfaces render identically. Returns empty when there is no current game.
 */
@Component
public class AnnouncementAssembler {

  private final QueuePort queuePort;
  private final RotationStatePort rotationPort;
  private final UpvotePort upvotePort;
  private final ArtResolutionChain artChain;

  public AnnouncementAssembler(
      QueuePort queuePort,
      RotationStatePort rotationPort,
      UpvotePort upvotePort,
      ArtResolutionChain artChain) {
    this.queuePort = queuePort;
    this.rotationPort = rotationPort;
    this.upvotePort = upvotePort;
    this.artChain = artChain;
  }

  public Optional<AnnouncementView> assemble(long guildId) {
    RotationState rotation = rotationPort.get(guildId);
    Optional<QueueSlot> current = rotation.currentSlot().flatMap(queuePort::findSlot);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    QueueSlot game = current.get();
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
    String art =
        artChain
            .coverFor(game.identity(), game.game().largeImageUrl(), game.game().name())
            .orElse(null);
    return Optional.of(
        new AnnouncementView(guildId, game.game().name(), game.proposerMemberId(), art, upNext));
  }
}
