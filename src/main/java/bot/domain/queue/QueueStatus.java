package bot.domain.queue;

/**
 * A queue slot's lifecycle state. {@code QUEUED → PLAYED} is one-way (a popped slot never returns).
 */
public enum QueueStatus {
  QUEUED,
  PLAYED
}
