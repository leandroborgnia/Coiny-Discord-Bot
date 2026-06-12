package bot.domain.queue;

import java.time.Instant;
import java.util.Optional;

/**
 * The per-guild rotation clock: the current designated slot (or none), the monotonic week number,
 * and {@code lastPopAt} (null until the bootstrap instant-pop starts the rolling-7-day clock).
 */
public record RotationState(
    long guildId, Long currentSlotId, int currentWeekNumber, Instant lastPopAt) {

  public Optional<Long> currentSlot() {
    return Optional.ofNullable(currentSlotId);
  }

  public Optional<Instant> lastPop() {
    return Optional.ofNullable(lastPopAt);
  }

  /** True before any game has ever been designated (initial state — instant-pop applies). */
  public boolean isUninitialized() {
    return currentSlotId == null && lastPopAt == null;
  }
}
