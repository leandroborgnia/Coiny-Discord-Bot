package bot.application.coin;

/**
 * Outcome of an adjustment. {@code APPLIED} for a new movement; {@code DUPLICATE} when the same
 * interaction id was already applied (no change). Failures (overdraw, unauthorized, …) are thrown
 * as typed {@code DomainException}s, not returned here.
 */
public record AdjustCoinsResult(
    Outcome outcome, int newBalance, int creditedAmount, int forfeitedAmount, int cap) {

  public enum Outcome {
    APPLIED,
    DUPLICATE
  }
}
