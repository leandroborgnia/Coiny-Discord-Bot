package bot.domain.queue;

import bot.domain.DomainException;

/**
 * Thrown when a propose or bump would cost more than the member's balance. Carries the current
 * balance for the rendered message; throwing rolls the transaction back so nothing changes (FR-002,
 * SC-002).
 */
public class InsufficientCoinsException extends DomainException {

  private final int balance;

  public InsufficientCoinsException(int balance) {
    super("queue.error.insufficient", balance);
    this.balance = balance;
  }

  public int balance() {
    return balance;
  }
}
