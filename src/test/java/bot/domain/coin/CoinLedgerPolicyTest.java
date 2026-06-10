package bot.domain.coin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pure unit tests for the coin posting policy — grant/cap/forfeit/deduct/overdraw arithmetic. */
class CoinLedgerPolicyTest {

  private static final long GUILD = 1L;
  private static final long MEMBER = 2L;

  @Test
  void grantUnderCapCreditsFullAmount() {
    PostingPlan plan = CoinLedgerPolicy.planGrant(GUILD, MEMBER, 0, 50, 100);

    assertThat(plan.credited()).isEqualTo(50);
    assertThat(plan.forfeited()).isZero();
    assertThat(plan.requested()).isEqualTo(50);
    assertThat(memberDelta(plan)).isEqualTo(50);
    assertBalanced(plan);
  }

  @Test
  void grantPartiallyOverCapCreditsHeadroomAndForfeitsRest() {
    PostingPlan plan = CoinLedgerPolicy.planGrant(GUILD, MEMBER, 80, 50, 100);

    assertThat(plan.credited()).isEqualTo(20);
    assertThat(plan.forfeited()).isEqualTo(30);
    assertThat(memberDelta(plan)).isEqualTo(20);
    assertThat(forfeitTotal(plan)).isEqualTo(30);
    assertBalanced(plan);
  }

  @Test
  void grantAtCapCreditsNothingAndForfeitsAllButStillRecords() {
    PostingPlan plan = CoinLedgerPolicy.planGrant(GUILD, MEMBER, 100, 10, 100);

    assertThat(plan.credited()).isZero();
    assertThat(plan.forfeited()).isEqualTo(10);
    assertThat(plan.lines()).noneMatch(l -> l.account() == LedgerAccount.MEMBER);
    assertBalanced(plan);
  }

  @Test
  void deductionDebitsMemberAndCreditsTreasury() {
    PostingPlan plan = CoinLedgerPolicy.planDeduction(GUILD, MEMBER, 30, 20);

    assertThat(memberDelta(plan)).isEqualTo(-20);
    assertThat(plan.credited()).isZero();
    assertThat(plan.forfeited()).isZero();
    assertBalanced(plan);
  }

  @Test
  void deductionToExactlyZeroIsAllowed() {
    PostingPlan plan = CoinLedgerPolicy.planDeduction(GUILD, MEMBER, 20, 20);

    assertThat(memberDelta(plan)).isEqualTo(-20);
    assertBalanced(plan);
  }

  @Test
  void deductionBeyondBalanceOverdraws() {
    assertThatThrownBy(() -> CoinLedgerPolicy.planDeduction(GUILD, MEMBER, 30, 100))
        .isInstanceOf(OverdrawException.class);
  }

  @Test
  void nonPositiveAmountsAreRejected() {
    assertThatThrownBy(() -> CoinLedgerPolicy.planGrant(GUILD, MEMBER, 0, 0, 100))
        .isInstanceOf(NonPositiveAmountException.class);
    assertThatThrownBy(() -> CoinLedgerPolicy.planDeduction(GUILD, MEMBER, 0, -5))
        .isInstanceOf(NonPositiveAmountException.class);
  }

  @Test
  void noPlanEverPostsBetweenTwoMembers() {
    PostingPlan[] plans = {
      CoinLedgerPolicy.planGrant(GUILD, MEMBER, 0, 50, 100),
      CoinLedgerPolicy.planGrant(GUILD, MEMBER, 80, 50, 100),
      CoinLedgerPolicy.planGrant(GUILD, MEMBER, 100, 10, 100),
      CoinLedgerPolicy.planDeduction(GUILD, MEMBER, 30, 20)
    };
    for (PostingPlan plan : plans) {
      long memberLines =
          plan.lines().stream().filter(l -> l.account() == LedgerAccount.MEMBER).count();
      assertThat(memberLines).isLessThanOrEqualTo(1);
    }
  }

  private static int memberDelta(PostingPlan plan) {
    return plan.lines().stream()
        .filter(l -> l.account() == LedgerAccount.MEMBER)
        .mapToInt(PostingLine::signedAmount)
        .sum();
  }

  private static int forfeitTotal(PostingPlan plan) {
    return plan.lines().stream()
        .filter(l -> l.account() == LedgerAccount.FORFEIT)
        .mapToInt(PostingLine::signedAmount)
        .sum();
  }

  private static void assertBalanced(PostingPlan plan) {
    assertThat(plan.lines().stream().mapToInt(PostingLine::signedAmount).sum()).isZero();
  }
}
