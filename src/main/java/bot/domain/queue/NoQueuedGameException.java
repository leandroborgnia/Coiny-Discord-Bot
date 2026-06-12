package bot.domain.queue;

import bot.domain.DomainException;

/** Thrown when a member bumps or withdraws but has no game currently in the queue. */
public class NoQueuedGameException extends DomainException {

  public NoQueuedGameException() {
    super("queue.error.no-queued");
  }
}
