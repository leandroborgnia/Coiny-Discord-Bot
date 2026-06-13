package bot.application.participation;

/**
 * Outcome of one member's accrual tick: how many drops were minted, the coins credited and
 * forfeited across them, the banked remainder after the tick, and a coarse {@link Outcome} for
 * logging/tests.
 */
public record AccrueParticipationResult(
    int dropsMinted,
    int coinsCredited,
    int coinsForfeited,
    long bankedSecondsAfter,
    Outcome outcome) {

  public enum Outcome {
    /** Time banked, no whole drop minted this tick. */
    ACCRUED,
    /** Balance already at the cap — accrual paused, nothing banked. */
    PAUSED_AT_CAP,
    /** No prior sample or a gap beyond max-gap — only {@code last_sampled_at} advanced. */
    FRESH_SESSION,
    /** One or more whole drops minted this tick. */
    MINTED
  }
}
