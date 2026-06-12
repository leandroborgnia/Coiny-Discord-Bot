package bot.domain.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the rolling-7-day rotation arithmetic. */
class RotationPolicyTest {

  private static final Instant LAST_POP = Instant.parse("2026-06-01T00:00:00Z");

  @Test
  void noAdvanceBeforeAFullWeek() {
    assertThat(RotationPolicy.advancesDue(LAST_POP, LAST_POP)).isZero();
    assertThat(RotationPolicy.advancesDue(LAST_POP, LAST_POP.plus(Duration.ofDays(6)))).isZero();
    assertThat(
            RotationPolicy.advancesDue(LAST_POP, LAST_POP.plus(Duration.ofDays(6).plusHours(23))))
        .isZero();
  }

  @Test
  void oneAdvanceAtAndWithinTheFirstWeekBoundary() {
    assertThat(RotationPolicy.advancesDue(LAST_POP, LAST_POP.plus(RotationPolicy.WEEK)))
        .isEqualTo(1);
    assertThat(RotationPolicy.advancesDue(LAST_POP, LAST_POP.plus(Duration.ofDays(10))))
        .isEqualTo(1);
  }

  @Test
  void multiplePeriodsForDowntimeCatchUp() {
    assertThat(RotationPolicy.advancesDue(LAST_POP, LAST_POP.plus(Duration.ofDays(14))))
        .isEqualTo(2);
    assertThat(RotationPolicy.advancesDue(LAST_POP, LAST_POP.plus(Duration.ofDays(23))))
        .isEqualTo(3);
  }

  @Test
  void nullOrPastNowYieldsZero() {
    assertThat(RotationPolicy.advancesDue(null, LAST_POP)).isZero();
    assertThat(RotationPolicy.advancesDue(LAST_POP, LAST_POP.minus(Duration.ofDays(1)))).isZero();
  }

  @Test
  void nextPopAtAdvancesByWholeWeeks() {
    assertThat(RotationPolicy.nextPopAt(LAST_POP, 1)).isEqualTo(LAST_POP.plus(Duration.ofDays(7)));
    assertThat(RotationPolicy.nextPopAt(LAST_POP, 3)).isEqualTo(LAST_POP.plus(Duration.ofDays(21)));
  }
}
