package bot.domain.coin;

import java.util.List;

/**
 * The balanced set of postings for a single movement, plus the event facts the movement header
 * records ({@code requested}/{@code credited}/{@code forfeited}). The {@code lines} always sum to
 * zero and never contain two {@code MEMBER} lines (coins are non-transferable).
 */
public record PostingPlan(
    AdjustmentType type, int requested, int credited, int forfeited, List<PostingLine> lines) {

  public PostingPlan {
    lines = List.copyOf(lines);
    int sum = lines.stream().mapToInt(PostingLine::signedAmount).sum();
    if (sum != 0) {
      throw new IllegalStateException("posting plan is not balanced (sum=" + sum + ")");
    }
    long memberLines = lines.stream().filter(l -> l.account() == LedgerAccount.MEMBER).count();
    if (memberLines > 1) {
      throw new IllegalStateException("a movement may not post between two member accounts");
    }
  }
}
