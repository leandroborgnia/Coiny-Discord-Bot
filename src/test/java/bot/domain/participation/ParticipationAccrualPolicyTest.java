package bot.domain.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the participation accrual time-arithmetic policy. */
class ParticipationAccrualPolicyTest {

  private static final Duration MAX_GAP = Duration.ofMinutes(2);
  private static final Instant NOW = Instant.parse("2026-06-13T12:00:00Z");

  @Test
  void elapsedIsZeroWhenNeverSampled() {
    assertThat(ParticipationAccrualPolicy.elapsedToCredit(null, NOW, MAX_GAP)).isZero();
  }

  @Test
  void elapsedIsZeroWhenGapExceedsMaxGap() {
    Instant tooLongAgo = NOW.minus(Duration.ofMinutes(5));
    assertThat(ParticipationAccrualPolicy.elapsedToCredit(tooLongAgo, NOW, MAX_GAP)).isZero();
  }

  @Test
  void elapsedIsTheRealDeltaWithinMaxGap() {
    Instant ninetySecondsAgo = NOW.minusSeconds(90);
    assertThat(ParticipationAccrualPolicy.elapsedToCredit(ninetySecondsAgo, NOW, MAX_GAP))
        .isEqualTo(90);
  }

  @Test
  void elapsedAtExactlyMaxGapIsCredited() {
    Instant exactlyMaxGapAgo = NOW.minus(MAX_GAP);
    assertThat(ParticipationAccrualPolicy.elapsedToCredit(exactlyMaxGapAgo, NOW, MAX_GAP))
        .isEqualTo(MAX_GAP.getSeconds());
  }

  @Test
  void elapsedIsZeroForAClockGoingBackwards() {
    Instant future = NOW.plusSeconds(30);
    assertThat(ParticipationAccrualPolicy.elapsedToCredit(future, NOW, MAX_GAP)).isZero();
  }

  @Test
  void thresholdSecondsIsMinutesTimesSixty() {
    assertThat(ParticipationAccrualPolicy.thresholdSeconds(new ParticipationRate(60, 1)))
        .isEqualTo(3600);
    assertThat(ParticipationAccrualPolicy.thresholdSeconds(new ParticipationRate(1, 5)))
        .isEqualTo(60);
  }

  @Test
  void dropsReadySplitsBankedIntoWholeDropsAndRemainder() {
    DropsAndRemainder result = ParticipationAccrualPolicy.dropsReady(130, 60);

    assertThat(result.drops()).isEqualTo(2);
    assertThat(result.remainderSeconds()).isEqualTo(10);
  }

  @Test
  void dropsReadyIsZeroBelowThreshold() {
    DropsAndRemainder result = ParticipationAccrualPolicy.dropsReady(59, 60);

    assertThat(result.drops()).isZero();
    assertThat(result.remainderSeconds()).isEqualTo(59);
  }

  @Test
  void dropsReadyRejectsNonPositiveThreshold() {
    assertThatThrownBy(() -> ParticipationAccrualPolicy.dropsReady(100, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
