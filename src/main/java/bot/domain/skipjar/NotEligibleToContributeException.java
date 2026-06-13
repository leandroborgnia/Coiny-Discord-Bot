package bot.domain.skipjar;

import bot.domain.DomainException;

/**
 * Thrown when the participation gate is on and a non-earner tries to contribute — only members who
 * earned coins from the current game may vote to skip it (FR-004). Carries the game's display name
 * for the rendered message; throwing rolls the transaction back so no coin is charged.
 */
public class NotEligibleToContributeException extends DomainException {

  public NotEligibleToContributeException(String gameName) {
    super("skip.error.not-earner", gameName);
  }
}
