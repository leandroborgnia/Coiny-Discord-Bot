package bot.domain.queue;

import java.util.List;
import java.util.Optional;

/**
 * The assembled ephemeral queue view (FR-028): the current week's game (or empty), the next five
 * queued slots, the viewer's own queued slot always included/marked (even beyond the top five), and
 * the viewer's eligibility to propose. Each slot carries its snapshot upvote count.
 *
 * <p>Pure domain type — no framework imports.
 */
public record QueueView(
    Optional<QueueSlot> currentGame,
    List<QueueSlot> nextFive,
    Optional<QueueSlot> ownEntry,
    boolean eligibleToPropose,
    int gamesRemaining) {

  public QueueView {
    nextFive = List.copyOf(nextFive);
  }
}
