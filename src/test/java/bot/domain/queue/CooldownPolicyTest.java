package bot.domain.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure unit tests for the "wait N games" cooldown arithmetic (FR-011/FR-012). */
class CooldownPolicyTest {

  @Test
  void nAtPopIsTheNumberOfOthersWaiting() {
    assertThat(CooldownPolicy.nReached(0)).isZero(); // nobody else waiting => propose again now
    assertThat(CooldownPolicy.nReached(4)).isEqualTo(4);
  }

  @Test
  void eligibleOnlyWithNoQueuedGameAndZeroRemaining() {
    assertThat(CooldownPolicy.eligible(false, 0)).isTrue();
    assertThat(CooldownPolicy.eligible(false, 2)).isFalse(); // still in cooldown
    assertThat(CooldownPolicy.eligible(true, 0)).isFalse(); // already holds a queued game
  }

  @Test
  void decrementCountsDownAndFloorsAtZero() {
    assertThat(CooldownPolicy.decrement(3)).isEqualTo(2);
    assertThat(CooldownPolicy.decrement(1)).isZero();
    assertThat(CooldownPolicy.decrement(0)).isZero();
  }
}
