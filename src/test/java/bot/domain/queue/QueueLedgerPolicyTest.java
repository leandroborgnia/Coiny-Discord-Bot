package bot.domain.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bot.domain.coin.AdjustmentType;
import bot.domain.coin.LedgerAccount;
import bot.domain.coin.PostingLine;
import bot.domain.coin.PostingPlan;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the queue posting policy — balanced MEMBER↔POT spends and refunds. */
class QueueLedgerPolicyTest {

  private static final long MEMBER = 42L;

  @Test
  void proposeSpendDebitsMemberAndCreditsPotZeroSum() {
    PostingPlan plan = QueueLedgerPolicy.planSpend(MEMBER, 10, 1, AdjustmentType.QUEUE_PROPOSE);

    assertThat(plan.type()).isEqualTo(AdjustmentType.QUEUE_PROPOSE);
    assertThat(plan.requested()).isEqualTo(1);
    assertThat(memberDelta(plan)).isEqualTo(-1);
    assertThat(potDelta(plan)).isEqualTo(1);
    assertBalanced(plan);
  }

  @Test
  void bumpSpendCarriesBumpType() {
    PostingPlan plan = QueueLedgerPolicy.planSpend(MEMBER, 5, 3, AdjustmentType.QUEUE_BUMP);

    assertThat(plan.type()).isEqualTo(AdjustmentType.QUEUE_BUMP);
    assertThat(memberDelta(plan)).isEqualTo(-3);
    assertThat(potDelta(plan)).isEqualTo(3);
    assertBalanced(plan);
  }

  @Test
  void spendingExactlyTheWholeBalanceIsAllowed() {
    PostingPlan plan = QueueLedgerPolicy.planSpend(MEMBER, 1, 1, AdjustmentType.QUEUE_PROPOSE);

    assertThat(memberDelta(plan)).isEqualTo(-1);
    assertBalanced(plan);
  }

  @Test
  void spendBeyondBalanceThrowsInsufficientCoins() {
    assertThatThrownBy(
            () -> QueueLedgerPolicy.planSpend(MEMBER, 0, 1, AdjustmentType.QUEUE_PROPOSE))
        .isInstanceOf(InsufficientCoinsException.class)
        .satisfies(e -> assertThat(((InsufficientCoinsException) e).balance()).isZero());
  }

  @Test
  void refundCreditsMemberAndDebitsPotZeroSum() {
    PostingPlan plan = QueueLedgerPolicy.planRefund(MEMBER, 4);

    assertThat(plan.type()).isEqualTo(AdjustmentType.QUEUE_REFUND);
    assertThat(plan.requested()).isEqualTo(4);
    assertThat(memberDelta(plan)).isEqualTo(4);
    assertThat(potDelta(plan)).isEqualTo(-4);
    assertBalanced(plan);
  }

  @Test
  void nonPositiveAmountsAreRejected() {
    assertThatThrownBy(
            () -> QueueLedgerPolicy.planSpend(MEMBER, 10, 0, AdjustmentType.QUEUE_PROPOSE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> QueueLedgerPolicy.planRefund(MEMBER, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSpendMustCarryASpendType() {
    assertThatThrownBy(() -> QueueLedgerPolicy.planSpend(MEMBER, 10, 1, AdjustmentType.GRANT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static int memberDelta(PostingPlan plan) {
    return plan.lines().stream()
        .filter(l -> l.account() == LedgerAccount.MEMBER)
        .mapToInt(PostingLine::signedAmount)
        .sum();
  }

  private static int potDelta(PostingPlan plan) {
    return plan.lines().stream()
        .filter(l -> l.account() == LedgerAccount.POT)
        .mapToInt(PostingLine::signedAmount)
        .sum();
  }

  private static void assertBalanced(PostingPlan plan) {
    assertThat(plan.lines().stream().mapToInt(PostingLine::signedAmount).sum()).isZero();
  }
}
