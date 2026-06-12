package bot.application.queue;

import bot.domain.queue.AnnouncementView;
import java.util.Optional;

/**
 * Outcome of a propose. {@code PROPOSED} (added at {@code position}); {@code INSTANT_POPPED}
 * (became this week's game); {@code REPLACED} (updated the existing slot, free); {@code DUPLICATE}
 * (same interaction already applied); {@code NO_ACTIVITY} (no readable game — nothing charged or
 * changed). Affordability and cooldown failures are thrown as typed {@code DomainException}s, not
 * returned.
 *
 * <p>{@code announcement} is present only on an instant-pop when an announcement channel is
 * configured — the designated current game to post (FR-024/FR-036); the handler posts it after the
 * transaction commits.
 */
public record ProposeGameResult(
    Outcome outcome,
    int position,
    boolean instantPop,
    int coinsSpent,
    int newBalance,
    Optional<AnnouncementView> announcement) {

  /** Convenience for the non-announcing outcomes (no live announcement to post). */
  public ProposeGameResult(
      Outcome outcome, int position, boolean instantPop, int coinsSpent, int newBalance) {
    this(outcome, position, instantPop, coinsSpent, newBalance, Optional.empty());
  }

  public enum Outcome {
    PROPOSED,
    INSTANT_POPPED,
    REPLACED,
    DUPLICATE,
    NO_ACTIVITY
  }
}
