package bot.domain.queue;

import bot.domain.coin.AdjustmentType;
import bot.domain.coin.PostingLine;
import bot.domain.coin.PostingPlan;
import java.util.List;

/**
 * Pure economy arithmetic for the game queue — no I/O. Builds the balanced {@link PostingPlan}s
 * that post queue spends and the withdraw refund through the existing append-only coin ledger
 * (MEMBER ↔ POT). Reuses {@code bot.domain.coin} types; unit-tested without a DB.
 *
 * <p>A spend debits the member and credits the per-guild pot; a refund reverses it. The plans are
 * zero-sum and carry the queue {@link AdjustmentType} so the ledger records the right movement
 * type.
 */
public final class QueueLedgerPolicy {

  private QueueLedgerPolicy() {}

  /**
   * Plan a queue spend (propose or bump). Throws {@link InsufficientCoinsException} when the member
   * cannot afford it, so the transaction rolls back and nothing is posted (FR-002).
   *
   * @param type one of {@link AdjustmentType#QUEUE_PROPOSE} or {@link AdjustmentType#QUEUE_BUMP}
   */
  public static PostingPlan planSpend(
      long memberId, int currentBalance, int cost, AdjustmentType type) {
    if (cost <= 0) {
      throw new IllegalArgumentException("queue cost must be at least 1 (was " + cost + ")");
    }
    if (type != AdjustmentType.QUEUE_PROPOSE && type != AdjustmentType.QUEUE_BUMP) {
      throw new IllegalArgumentException("spend type must be QUEUE_PROPOSE or QUEUE_BUMP: " + type);
    }
    if (currentBalance < cost) {
      throw new InsufficientCoinsException(currentBalance);
    }
    List<PostingLine> lines = List.of(PostingLine.member(memberId, -cost), PostingLine.pot(cost));
    return new PostingPlan(type, cost, 0, 0, lines);
  }

  /**
   * Plan a withdraw refund: credit the member, debit the pot (FR-033). A new reversing movement —
   * posted rows are never edited (Principle III).
   */
  public static PostingPlan planRefund(long memberId, int amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("refund amount must be at least 1 (was " + amount + ")");
    }
    List<PostingLine> lines =
        List.of(PostingLine.member(memberId, amount), PostingLine.pot(-amount));
    return new PostingPlan(AdjustmentType.QUEUE_REFUND, amount, 0, 0, lines);
  }
}
