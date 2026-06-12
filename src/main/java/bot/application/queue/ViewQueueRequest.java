package bot.application.queue;

/** Request to view the current game and queue (ephemeral, scoped to the requesting member). */
public record ViewQueueRequest(long guildId, long memberId) {}
