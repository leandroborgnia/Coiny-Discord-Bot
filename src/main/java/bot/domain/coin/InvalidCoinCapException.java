package bot.domain.coin;

import bot.domain.DomainException;

/** Thrown when a server attempts to configure a negative balance cap (a cap of 0 is valid). */
public class InvalidCoinCapException extends DomainException {

  public InvalidCoinCapException(int cap) {
    super("coin.error.invalid-cap", cap);
  }
}
