package bot.domain.queue;

import java.util.UUID;

/**
 * Outbound port for per-slot upvotes (mutable social state, NOT the coin ledger). Upvotes bind to a
 * specific {@code gameInstanceId} (this appearance of the game — FR-030); the count is scoped to
 * the current instance so a replace resets it to zero.
 */
public interface UpvotePort {

  /**
   * Toggle the member's upvote on this appearance; returns {@code true} iff the state actually
   * changed (idempotent across multiple ephemeral renders — FR-031).
   */
  boolean toggle(long slotId, long memberId, UUID gameInstanceId);

  /** The slot's upvote count for the given appearance only. */
  int count(long slotId, UUID gameInstanceId);

  /** Cleanup of prior-instance upvote rows on replace (correctness does not depend on it). */
  void resetForSlot(long slotId);
}
