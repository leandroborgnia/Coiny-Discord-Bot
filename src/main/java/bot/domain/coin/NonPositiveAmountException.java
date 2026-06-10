package bot.domain.coin;

import bot.domain.DomainException;

/** Thrown when an adjustment amount is zero or negative (the smallest unit is 1). */
public class NonPositiveAmountException extends DomainException {

  public NonPositiveAmountException(int amount) {
    super("coin.error.non-positive", amount);
  }
}
