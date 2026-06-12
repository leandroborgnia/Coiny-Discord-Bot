package bot.domain.queue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The assembled ephemeral queue view (FR-028): the current week's game (or empty), the next five
 * queued slots, and the viewer's own queued slot (always included so it can be marked, even when it
 * falls beyond the top five), plus the viewer's eligibility. Each shown {@link Entry} carries its
 * snapshot upvote count and a resolved cover-art url (via the art chain; null = render name-only).
 *
 * <p>Pure domain type — no framework imports. Assembled by {@code ViewQueueService}.
 */
public record QueueView(
    Optional<Entry> currentGame,
    List<Entry> upNext,
    Optional<Entry> ownEntry,
    boolean ownEntryShownInUpNext,
    boolean eligibleToPropose,
    int gamesRemaining) {

  public QueueView {
    upNext = List.copyOf(upNext);
  }

  /** One rendered slot: identity for the upvote button, plus display fields and resolved art. */
  public record Entry(
      long slotId,
      UUID gameInstanceId,
      long proposerId,
      Integer position,
      String gameName,
      int upvoteCount,
      String coverUrl) {}
}
