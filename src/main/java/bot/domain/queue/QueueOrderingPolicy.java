package bot.domain.queue;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Pure ordering arithmetic for the queue — no I/O. Expresses the rules the persistence adapter
 * realizes atomically in SQL: a new slot joins at the tail; after a pop/withdraw the remaining
 * slots keep their relative order as a dense {@code 1..n}; a bump swaps a slot one position up
 * (FR-004/007/008).
 */
public final class QueueOrderingPolicy {

  private QueueOrderingPolicy() {}

  /** The tail position for a new slot, given how many slots are currently queued (1-indexed). */
  public static int appendPosition(int currentlyQueued) {
    if (currentlyQueued < 0) {
      throw new IllegalArgumentException("queued count cannot be negative: " + currentlyQueued);
    }
    return currentlyQueued + 1;
  }

  /** The dense positions {@code [1..remaining]} the surviving slots take after a removal/pop. */
  public static List<Integer> densePositions(int remaining) {
    if (remaining < 0) {
      throw new IllegalArgumentException("remaining count cannot be negative: " + remaining);
    }
    return IntStream.rangeClosed(1, remaining).boxed().toList();
  }

  /**
   * The new position of a slot bumped one step toward the top. The caller must have already
   * verified the slot is not at the top (position &gt; 1) — a top slot has nowhere to go (FR-006).
   */
  public static int bumpedPosition(int currentPosition) {
    if (currentPosition <= 1) {
      throw new IllegalArgumentException("a top slot cannot be bumped: " + currentPosition);
    }
    return currentPosition - 1;
  }
}
