package bot.domain.queue;

import bot.domain.DomainException;

/**
 * Thrown by {@code ConfigureQueueService} when the actor lacks the Manage Server permission
 * (FR-018/FR-037). Manage Server is the single authorization bar for queue configuration, matching
 * the command's {@code DefaultMemberPermissions} filter.
 */
public class NotAuthorizedException extends DomainException {

  public NotAuthorizedException() {
    super("queue.error.not-authorized");
  }
}
