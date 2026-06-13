package bot.domain.participation;

/**
 * Outbound port for per-server participation configuration (rate + free-first-proposal toggle).
 * Absent storage yields {@link GuildParticipationConfig#defaults(long)}.
 */
public interface ParticipationConfigPort {

  /** The server's config, or defaults (rate 60/1, toggle off) when none is stored yet. */
  GuildParticipationConfig get(long guildId);

  /** The free-first-proposal toggle alone — read by {@code ProposeGameService} (feature 004). */
  boolean freeFirstProposalEnabled(long guildId);

  /** Upsert the server's earning rate (both values are positive integers). */
  void setRate(long guildId, int minutesPerDrop, int coinsPerDrop);

  /** Upsert the server's free-first-proposal toggle. */
  void setFreeFirstProposal(long guildId, boolean enabled);
}
