package bot.domain.queue;

/**
 * Pure "wait N games" cooldown arithmetic — no I/O (FR-011/FR-012). When a member's game is popped,
 * N is fixed to the number of other games then waiting; the member becomes eligible only after N
 * more games are actually played. Empty weeks never decrement (the caller only decrements on a real
 * pop).
 */
public final class CooldownPolicy {

  private CooldownPolicy() {}

  /** N at pop = the number of other games still queued when this member's game is played. */
  public static int nReached(int otherQueuedCount) {
    return Math.max(0, otherQueuedCount);
  }

  /** A member may propose iff they hold no queued game and their remaining count has reached 0. */
  public static boolean eligible(boolean hasQueued, int gamesRemaining) {
    return !hasQueued && gamesRemaining <= 0;
  }

  /** One real pop counts the cooldown down by one, floored at 0. */
  public static int decrement(int gamesRemaining) {
    return Math.max(0, gamesRemaining - 1);
  }
}
