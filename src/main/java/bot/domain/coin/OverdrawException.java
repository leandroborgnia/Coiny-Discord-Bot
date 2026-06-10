package bot.domain.coin;

import bot.domain.DomainException;

/**
 * Thrown when a deduction would drive a member's balance below zero. Carries the member id and the
 * current balance for the rendered message; throwing rolls the transaction back so nothing changes.
 */
public class OverdrawException extends DomainException {

  private final long memberId;
  private final int currentBalance;

  public OverdrawException(long memberId, int currentBalance) {
    super("coin.error.overdraw", memberId, currentBalance);
    this.memberId = memberId;
    this.currentBalance = currentBalance;
  }

  public long memberId() {
    return memberId;
  }

  public int currentBalance() {
    return currentBalance;
  }
}
