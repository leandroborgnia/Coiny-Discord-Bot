package bot.domain.queue;

import bot.domain.DomainException;

/**
 * Thrown when propose (new or replace) runs but the member has no readable game activity / Rich
 * Presence (FR-035): nothing is charged or changed, and the reply advises checking activity-sharing
 * settings. (Services may instead represent this as a {@code NO_ACTIVITY} result outcome before any
 * lock/charge; either way no mutation occurs.)
 */
public class NoGameActivityException extends DomainException {

  public NoGameActivityException() {
    super("queue.error.no-activity");
  }
}
