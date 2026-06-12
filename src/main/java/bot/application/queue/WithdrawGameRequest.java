package bot.application.queue;

/**
 * Request to withdraw the member's own still-queued game (refunds the coins spent on it, FR-033).
 */
public record WithdrawGameRequest(long guildId, long memberId, long interactionId) {}
