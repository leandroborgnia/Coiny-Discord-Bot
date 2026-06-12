package bot.domain.queue;

/**
 * Outbound port for the "wait N games" re-proposal cooldown (FR-011/FR-012). The single atomic
 * source of truth for proposal eligibility (Constitution Principle IV) — no in-memory duplicate.
 */
public interface CooldownPort {

  /** How many more games must be played before the member may propose again (0 if no row). */
  int gamesRemaining(long guildId, long memberId);

  /** Fix the member's remaining count to N at the moment their game is popped. */
  void set(long guildId, long memberId, int n);

  /**
   * Decrement every member's remaining count (floored at 0) — only on a real pop, not empty weeks.
   */
  void decrementAll(long guildId);
}
