package bot.domain.coin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guards FR-006 / SC-006: a coin movement may change a member's balance only against TREASURY or
 * FORFEIT — never by debiting another member. No posting plan may contain two MEMBER lines.
 */
class NonTransferabilityTest {

  @Test
  void noPostingPlanCreditsOneMemberByDebitingAnother() {
    PostingPlan[] plans = {
      CoinLedgerPolicy.planGrant(1L, 10L, 0, 50, 100),
      CoinLedgerPolicy.planGrant(1L, 10L, 90, 50, 100),
      CoinLedgerPolicy.planGrant(1L, 10L, 100, 25, 100),
      CoinLedgerPolicy.planDeduction(1L, 10L, 40, 40),
      CoinLedgerPolicy.planDeduction(1L, 10L, 40, 1)
    };
    for (PostingPlan plan : plans) {
      long memberLines =
          plan.lines().stream().filter(l -> l.account() == LedgerAccount.MEMBER).count();
      assertThat(memberLines).as("plan %s", plan.type()).isLessThanOrEqualTo(1);
      assertThat(plan.lines().stream().mapToInt(PostingLine::signedAmount).sum()).isZero();
    }
  }
}
