package bot.domain.coin;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure economy arithmetic — no I/O. Builds the balanced {@link PostingPlan} for an adjustment given
 * the member's current balance and the server cap. Over-cap coins are forfeited at earning time
 * (TREASURY→FORFEIT); a deduction larger than the balance is rejected as an overdraw.
 */
public final class CoinLedgerPolicy {

  private CoinLedgerPolicy() {}

  /**
   * Plan a grant: credit up to the cap, forfeit the remainder. {@code amount} must be ≥ 1 and the
   * balance/cap non-negative.
   */
  public static PostingPlan planGrant(
      long guildId, long memberId, int currentBalance, int amount, int cap) {
    if (amount <= 0) {
      throw new NonPositiveAmountException(amount);
    }
    int headroom = Math.max(0, cap - currentBalance);
    int credited = Math.min(amount, headroom);
    int forfeited = amount - credited;

    List<PostingLine> lines = new ArrayList<>();
    if (credited > 0) {
      lines.add(PostingLine.treasury(-credited));
      lines.add(PostingLine.member(memberId, credited));
    }
    if (forfeited > 0) {
      lines.add(PostingLine.treasury(-forfeited));
      lines.add(PostingLine.forfeit(forfeited));
    }
    return new PostingPlan(AdjustmentType.GRANT, amount, credited, forfeited, lines);
  }

  /** Plan a deduction: debit the member, credit the treasury. Rejects an overdraw. */
  public static PostingPlan planDeduction(
      long guildId, long memberId, int currentBalance, int amount) {
    if (amount <= 0) {
      throw new NonPositiveAmountException(amount);
    }
    if (amount > currentBalance) {
      throw new OverdrawException(memberId, currentBalance);
    }
    List<PostingLine> lines =
        List.of(PostingLine.member(memberId, -amount), PostingLine.treasury(amount));
    return new PostingPlan(AdjustmentType.DEDUCTION, amount, 0, 0, lines);
  }
}
