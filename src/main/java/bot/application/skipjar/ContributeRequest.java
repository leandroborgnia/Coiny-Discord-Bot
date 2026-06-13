package bot.application.skipjar;

import java.time.Instant;

/**
 * A request to cast a one-coin skip-jar vote for the caller's current game. {@code interactionId}
 * is the Discord slash interaction id (the at-most-once idempotency key); {@code now} is the
 * evaluation instant (dwell gate + earner-run boundary).
 */
public record ContributeRequest(long guildId, long memberId, long interactionId, Instant now) {}
