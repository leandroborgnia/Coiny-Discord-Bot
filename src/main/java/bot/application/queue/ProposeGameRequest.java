package bot.application.queue;

import bot.domain.queue.CapturedGame;

/**
 * Request to propose a game. {@code game} is the Rich-Presence capture (or {@code null} when the
 * member had no readable game activity — the no-activity guard, FR-035). {@code interactionId} is
 * the slash interaction id, used for at-most-once idempotency.
 */
public record ProposeGameRequest(
    long guildId, long memberId, CapturedGame game, long interactionId) {}
