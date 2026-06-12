package bot.domain.queue;

import java.time.Instant;
import java.util.List;

/** Outbound port for the per-guild rotation clock and the append-only weekly-designation log. */
public interface RotationStatePort {

  /** The guild's rotation state (uninitialized when no game has ever been designated). */
  RotationState get(long guildId);

  /** Every guild that has a rotation clock (was bootstrapped) — the set the catch-up iterates. */
  List<Long> guildsWithState();

  /** First instant-pop: set the current slot and start the rolling-7-day clock (FR-024). */
  void bootstrap(long guildId, long slotId, Instant at);

  /**
   * Record a weekly designation (audit log, FR-022); {@code slotId == null} marks an empty week.
   * Idempotent per {@code (guildId, weekNumber)} via {@code ON CONFLICT DO NOTHING} (FR-016).
   */
  void recordDesignation(long guildId, int week, Long slotId, GameIdentity identity, Instant at);

  /** Advance the clock: set the current slot/week and move {@code lastPopAt} forward by 7 days. */
  void advanceClock(long guildId, Long currentSlotId, int week, Instant lastPopAt);
}
