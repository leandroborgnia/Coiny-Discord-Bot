package bot.domain.queue;

import java.util.Optional;

/**
 * Per-server queue configuration: the propose/bump costs (default 1/1, FR-025) and the optional
 * announcement channel plus the latest live-count message location (FR-038). Absent servers resolve
 * to {@link #defaults(long)}.
 */
public record GuildQueueConfig(
    long guildId,
    int proposeCost,
    int bumpCost,
    Long announcementChannelId,
    Long latestAnnouncementChannelId,
    Long latestAnnouncementMessageId) {

  public static final int DEFAULT_PROPOSE_COST = 1;
  public static final int DEFAULT_BUMP_COST = 1;

  public static GuildQueueConfig defaults(long guildId) {
    return new GuildQueueConfig(guildId, DEFAULT_PROPOSE_COST, DEFAULT_BUMP_COST, null, null, null);
  }

  public Optional<Long> announcementChannel() {
    return Optional.ofNullable(announcementChannelId);
  }

  public boolean hasAnnouncementChannel() {
    return announcementChannelId != null;
  }
}
