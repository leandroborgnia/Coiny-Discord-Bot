package bot.domain.skipjar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the skip threshold: a majority {@code ⌊N/2⌋+1} of distinct earners, floored
 * at the configured minimum. With floor 3, the floor governs small earner sets and the majority
 * takes over once it exceeds the floor (US2 AS-3, quickstart #1).
 */
class SkipThresholdPolicyTest {

  @Test
  void floorGovernsSmallEarnerSets() {
    assertThat(SkipThresholdPolicy.threshold(0, 3)).isEqualTo(3); // majority 1 → floor 3
    assertThat(SkipThresholdPolicy.threshold(1, 3)).isEqualTo(3); // majority 1 → floor 3
    assertThat(SkipThresholdPolicy.threshold(2, 3)).isEqualTo(3); // majority 2 → floor 3
  }

  @Test
  void majorityTakesOverOnceItExceedsTheFloor() {
    assertThat(SkipThresholdPolicy.threshold(5, 3)).isEqualTo(3); // majority 3 = floor 3
    assertThat(SkipThresholdPolicy.threshold(6, 3)).isEqualTo(4); // majority 4 > floor 3
  }
}
