package bot.domain.skipjar;

import bot.domain.DomainException;

/** Thrown when a contribution or skip is attempted with no current game to skip (FR-019). */
public class NoCurrentGameException extends DomainException {

  public NoCurrentGameException() {
    super("skip.error.no-game");
  }
}
