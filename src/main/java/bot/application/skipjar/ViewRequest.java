package bot.application.skipjar;

import java.time.Instant;

/**
 * A request to view the current game's skip-jar status. {@code now} decides the open/not-open
 * state.
 */
public record ViewRequest(long guildId, Instant now) {}
