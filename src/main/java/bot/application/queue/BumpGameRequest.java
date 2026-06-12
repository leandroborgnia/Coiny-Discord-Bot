package bot.application.queue;

/** Request to bump the member's own queued game one position toward the top (FR-004). */
public record BumpGameRequest(long guildId, long memberId, long interactionId) {}
