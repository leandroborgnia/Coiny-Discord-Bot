package bot.domain.skipjar;

/**
 * Pure arithmetic for the skip threshold (FR-008/FR-009): the number of contributions needed to
 * retire the current game early is a <em>majority</em> of the distinct members who have earned
 * coins from it ({@code ⌊N/2⌋ + 1}), floored at a configurable minimum so a tiny earner set can
 * still require a meaningful vote. No I/O — unit-tested without a DB.
 */
public final class SkipThresholdPolicy {

  private SkipThresholdPolicy() {}

  /**
   * The contributions required to skip: {@code max(⌊distinctEarners/2⌋ + 1, floor)}.
   *
   * @param distinctEarners number of distinct members who earned coins from the current game (N ≥
   *     0)
   * @param floor the configured minimum (must be ≥ 1)
   */
  public static int threshold(int distinctEarners, int floor) {
    int majority = Math.floorDiv(distinctEarners, 2) + 1;
    return Math.max(majority, floor);
  }
}
