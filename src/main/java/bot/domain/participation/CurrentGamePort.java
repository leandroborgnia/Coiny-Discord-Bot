package bot.domain.participation;

import bot.domain.queue.GameIdentity;
import java.util.Optional;

/**
 * Outbound port reading the queue feature's current designated game for a guild — the only game
 * that qualifies for earning (FR-003/011). Empty when the guild has no current game, so the sweep
 * skips the guild entirely.
 */
public interface CurrentGamePort {

  /** The guild's current week's {@link GameIdentity}, or empty when none is designated. */
  Optional<GameIdentity> currentGameIdentity(long guildId);
}
