package bot.application.coin;

import bot.domain.coin.AdjustmentType;
import java.time.Instant;
import java.util.List;

/** A member's derived balance, the server cap, and their most recent movements (newest first). */
public record BalanceView(int balance, int cap, List<MovementSummary> recent) {

  /** One history line: the event facts a member sees for a past movement. */
  public record MovementSummary(
      AdjustmentType type,
      int requested,
      int credited,
      int forfeited,
      String reason,
      long moderatorId,
      Instant createdAt) {}
}
