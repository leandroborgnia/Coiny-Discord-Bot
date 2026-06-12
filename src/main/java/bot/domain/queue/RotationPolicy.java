package bot.domain.queue;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure rolling-7-day rotation arithmetic — no I/O (FR-032). Each designated game lasts exactly 7
 * days; the number of advances due is how many whole 7-day periods have elapsed since the last pop,
 * so a bot that was offline at the boundary catches up by applying each missed period exactly once.
 */
public final class RotationPolicy {

  /** One rotation period: a designated game lasts exactly seven days. */
  public static final Duration WEEK = Duration.ofDays(7);

  private RotationPolicy() {}

  /**
   * Whole 7-day periods elapsed since {@code lastPopAt} (0 if not yet a week, or before the pop).
   */
  public static int advancesDue(Instant lastPopAt, Instant now) {
    if (lastPopAt == null || now.isBefore(lastPopAt)) {
      return 0;
    }
    long periods = Duration.between(lastPopAt, now).dividedBy(WEEK);
    return (int) Math.max(0, periods);
  }

  /**
   * The instant the clock reaches after applying {@code periods} whole weeks from {@code
   * lastPopAt}.
   */
  public static Instant nextPopAt(Instant lastPopAt, int periods) {
    return lastPopAt.plus(WEEK.multipliedBy(periods));
  }
}
