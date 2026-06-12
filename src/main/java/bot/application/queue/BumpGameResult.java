package bot.application.queue;

/**
 * Outcome of a bump. {@code BUMPED} (moved up to {@code newPosition}, {@code coinsSpent} deducted);
 * {@code AT_TOP} (already at the top — nothing charged); {@code NO_QUEUED} (no queued game); {@code
 * DUPLICATE} (this bump interaction was already applied). An unaffordable bump throws {@code
 * InsufficientCoinsException} (nothing changes).
 */
public record BumpGameResult(Outcome outcome, int newPosition, int coinsSpent, int newBalance) {

  public enum Outcome {
    BUMPED,
    AT_TOP,
    NO_QUEUED,
    DUPLICATE
  }
}
