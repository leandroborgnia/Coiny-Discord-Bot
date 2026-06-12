package bot.domain.queue;

/** Outbound port for per-server queue configuration (costs + announcement channel). */
public interface QueueConfigPort {

  /** The server's config, or {@link GuildQueueConfig#defaults(long)} when none is stored yet. */
  GuildQueueConfig get(long guildId);

  /** Upsert the costs; a {@code null} leaves that cost unchanged. */
  void upsertCosts(long guildId, Integer proposeCost, Integer bumpCost);

  /** Set (or clear, when {@code null}) the announcement channel. */
  void setAnnouncementChannel(long guildId, Long channelId);

  /** Record the location of the latest announcement message (the live count surface — FR-038). */
  void setLatestAnnouncement(long guildId, long channelId, long messageId);
}
