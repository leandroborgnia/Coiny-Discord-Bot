package bot.domain.queue;

import bot.domain.DomainException;

/**
 * Thrown when a member tries to propose while still inside their "wait N games" cooldown (FR-011).
 * Carries how many more games must be played before they are eligible again.
 */
public class NotEligibleException extends DomainException {

  private final int gamesRemaining;

  public NotEligibleException(int gamesRemaining) {
    super("queue.error.cooldown", gamesRemaining);
    this.gamesRemaining = gamesRemaining;
  }

  public int gamesRemaining() {
    return gamesRemaining;
  }
}
