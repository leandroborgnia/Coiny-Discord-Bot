package bot.domain.skipjar;

/**
 * Outbound port for per-server skip-jar configuration (threshold floor, dwell, participation gate).
 */
public interface SkipJarConfigPort {

  /** The server's config, or {@link GuildSkipJarConfig#defaults(long)} when none is stored yet. */
  GuildSkipJarConfig get(long guildId);

  /** Set the minimum contributions to skip (upsert; {@code thresholdFloor >= 1}). */
  void setFloor(long guildId, int thresholdFloor);

  /** Set the dwell time before a jar opens, in seconds (upsert; {@code dwellSeconds >= 1}). */
  void setDwell(long guildId, long dwellSeconds);

  /** Toggle the participation gate (upsert; on ⇒ only earners may contribute). */
  void setGate(long guildId, boolean gateOn);
}
