package bot.domain.skipjar;

import java.time.Duration;

/**
 * Per-server skip-jar settings. {@code thresholdFloor} is the minimum number of contributions to
 * skip a game regardless of the majority calculation (default 3); {@code dwell} is the minimum time
 * a game must be current before its jar opens (default 24 h); {@code gateOn} restricts
 * contributions to earners when true (default on). An absent config row reads as {@link
 * #defaults(long)} — the jar works with no admin initialization.
 *
 * <p>Pure domain value object — no framework imports.
 */
public record GuildSkipJarConfig(long guildId, int thresholdFloor, Duration dwell, boolean gateOn) {

  /** The effective configuration for a server that has stored no overrides. */
  public static GuildSkipJarConfig defaults(long guildId) {
    return new GuildSkipJarConfig(guildId, 3, Duration.ofHours(24), true);
  }
}
