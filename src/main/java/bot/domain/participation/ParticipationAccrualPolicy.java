package bot.domain.participation;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure accrual <em>time</em> arithmetic — no I/O, no coin/cap math. Clamps the elapsed interval to
 * credit (downtime and session re-entry accrue nothing, FR-023) and splits banked seconds into
 * whole drops plus the carried remainder. The coin amounts and cap forfeiture stay single-sourced
 * in the reused {@code CoinLedgerPolicy.planGrant}.
 */
public final class ParticipationAccrualPolicy {

  private ParticipationAccrualPolicy() {}

  /**
   * Seconds of qualifying time to credit for this tick: {@code 0} when {@code lastSampledAt} is
   * null (fresh session) or the gap exceeds {@code maxGap} (downtime / re-entry); otherwise the
   * real, non-negative gap in seconds.
   */
  public static long elapsedToCredit(Instant lastSampledAt, Instant now, Duration maxGap) {
    if (lastSampledAt == null) {
      return 0;
    }
    Duration gap = Duration.between(lastSampledAt, now);
    if (gap.isNegative()) {
      return 0;
    }
    if (gap.compareTo(maxGap) > 0) {
      return 0;
    }
    return gap.getSeconds();
  }

  /** The qualifying seconds that mint one drop: {@code minutesPerDrop * 60}. */
  public static long thresholdSeconds(ParticipationRate rate) {
    return (long) rate.minutesPerDrop() * 60L;
  }

  /**
   * Whole drops ready from {@code bankedSeconds} ({@code banked / threshold}) and the leftover
   * remainder ({@code banked % threshold}). {@code thresholdSeconds} must be positive.
   */
  public static DropsAndRemainder dropsReady(long bankedSeconds, long thresholdSeconds) {
    if (thresholdSeconds <= 0) {
      throw new IllegalArgumentException("thresholdSeconds must be > 0, was " + thresholdSeconds);
    }
    int drops = (int) (bankedSeconds / thresholdSeconds);
    long remainder = bankedSeconds % thresholdSeconds;
    return new DropsAndRemainder(drops, remainder);
  }
}
