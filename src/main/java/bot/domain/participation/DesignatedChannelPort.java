package bot.domain.participation;

import java.util.List;

/**
 * Outbound port for the per-server set of designated participation voice channels. {@code add} is
 * idempotent; {@code resetAll} clears the set (reset-to-none); there is no single-channel removal
 * (out of scope, FR-013). {@code guildsWithChannels} is the set the sweep iterates.
 */
public interface DesignatedChannelPort {

  /** Add a channel to the guild's designated set (idempotent). */
  void add(long guildId, long channelId);

  /** Reset the guild's designated set to none. */
  void resetAll(long guildId);

  /** The guild's designated channel ids (empty when none). */
  List<Long> list(long guildId);

  /** Whether the channel is in the guild's designated set. */
  boolean contains(long guildId, long channelId);

  /** Distinct guild ids that have at least one designated channel — the sweep's iteration set. */
  List<Long> guildsWithChannels();
}
