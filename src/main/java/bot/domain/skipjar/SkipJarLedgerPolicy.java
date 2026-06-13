package bot.domain.skipjar;

import bot.domain.coin.AdjustmentType;
import bot.domain.coin.OverdrawException;
import bot.domain.coin.PostingLine;
import bot.domain.coin.PostingPlan;
import java.util.List;

/**
 * Pure economy arithmetic for a skip-jar contribution — no I/O. Builds the balanced {@link
 * PostingPlan} that posts the one non-refundable coin through the existing append-only coin ledger
 * (MEMBER −1 / SKIP_POT +1). Unit-tested without a DB.
 *
 * <p>There is deliberately <strong>no</strong> {@code planRefund} for SKIP_JAR — coins paid into
 * the skip jar are never returned (FR-003); no code path ever reverses a SKIP_JAR movement.
 */
public final class SkipJarLedgerPolicy {

  private SkipJarLedgerPolicy() {}

  /**
   * Plan the one-coin contribution. Throws {@link OverdrawException} when the member cannot afford
   * it (balance &lt; 1), so the transaction rolls back and nothing is posted (FR-006).
   */
  public static PostingPlan planContribution(long memberId, int currentBalance) {
    if (currentBalance < 1) {
      throw new OverdrawException(memberId, currentBalance);
    }
    List<PostingLine> lines = List.of(PostingLine.member(memberId, -1), PostingLine.skipPot(1));
    return new PostingPlan(AdjustmentType.SKIP_JAR, 1, 0, 0, lines);
  }
}
