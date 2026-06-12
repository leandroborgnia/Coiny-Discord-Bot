package bot.domain.queue;

/**
 * A pointer to the single live announcement message per guild (FR-038) — the one surface edited on
 * each registered upvote change. Returned by {@code UpvoteService} when a live surface exists.
 */
public record AnnouncementRef(long guildId, long channelId, long messageId) {}
