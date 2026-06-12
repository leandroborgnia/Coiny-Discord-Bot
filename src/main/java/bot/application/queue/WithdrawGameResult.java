package bot.application.queue;

/**
 * Outcome of a withdraw. {@code WITHDRAWN} (slot removed, {@code refunded} coins returned); {@code
 * NO_QUEUED} (the member has no queued game); {@code DUPLICATE} (this withdraw interaction was
 * already applied).
 */
public record WithdrawGameResult(Outcome outcome, int refunded, int newBalance) {

  public enum Outcome {
    WITHDRAWN,
    NO_QUEUED,
    DUPLICATE
  }
}
