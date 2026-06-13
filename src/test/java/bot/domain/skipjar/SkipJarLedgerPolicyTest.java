package bot.domain.skipjar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bot.domain.coin.AdjustmentType;
import bot.domain.coin.LedgerAccount;
import bot.domain.coin.OverdrawException;
import bot.domain.coin.PostingLine;
import bot.domain.coin.PostingPlan;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the skip-jar posting policy — a balanced, non-refundable MEMBER↔SKIP_POT
 * spend.
 */
class SkipJarLedgerPolicyTest {

  private static final long MEMBER = 42L;

  @Test
  void contributionDebitsMemberAndCreditsSkipPotZeroSum() {
    PostingPlan plan = SkipJarLedgerPolicy.planContribution(MEMBER, 5);

    assertThat(plan.type()).isEqualTo(AdjustmentType.SKIP_JAR);
    assertThat(plan.requested()).isEqualTo(1);
    assertThat(plan.credited()).isZero();
    assertThat(plan.forfeited()).isZero();
    assertThat(memberDelta(plan)).isEqualTo(-1);
    assertThat(skipPotDelta(plan)).isEqualTo(1);
    assertThat(plan.lines().stream().mapToInt(PostingLine::signedAmount).sum()).isZero();
  }

  @Test
  void contributingWithExactlyOneCoinIsAllowed() {
    PostingPlan plan = SkipJarLedgerPolicy.planContribution(MEMBER, 1);
    assertThat(memberDelta(plan)).isEqualTo(-1);
  }

  @Test
  void contributingWithNoCoinsThrowsOverdraw() {
    assertThatThrownBy(() -> SkipJarLedgerPolicy.planContribution(MEMBER, 0))
        .isInstanceOf(OverdrawException.class)
        .satisfies(
            e -> {
              assertThat(((OverdrawException) e).memberId()).isEqualTo(MEMBER);
              assertThat(((OverdrawException) e).currentBalance()).isZero();
            });
  }

  private static int memberDelta(PostingPlan plan) {
    return plan.lines().stream()
        .filter(l -> l.account() == LedgerAccount.MEMBER)
        .mapToInt(PostingLine::signedAmount)
        .sum();
  }

  private static int skipPotDelta(PostingPlan plan) {
    return plan.lines().stream()
        .filter(l -> l.account() == LedgerAccount.SKIP_POT)
        .mapToInt(PostingLine::signedAmount)
        .sum();
  }
}
