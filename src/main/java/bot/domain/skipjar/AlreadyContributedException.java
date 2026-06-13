package bot.domain.skipjar;

import bot.domain.DomainException;

/**
 * Thrown when a member tries to contribute a second time for the same run — once per member per run
 * (FR-002). Throwing rolls the transaction back so no additional coin is charged.
 */
public class AlreadyContributedException extends DomainException {

  public AlreadyContributedException() {
    super("skip.error.already");
  }
}
