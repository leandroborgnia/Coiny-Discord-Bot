package bot.domain.queue;

/**
 * Outbound port for the weekly-announcement live surface (FR-036/FR-038). Implemented over JDA REST
 * in {@code bot.infrastructure.discord}; sends/edits happen after the transaction commits, off the
 * interaction ack (Principle V).
 */
public interface AnnouncementPort {

  /** Post a new announcement; returns the created message id (stored as the live surface). */
  long post(long guildId, long channelId, AnnouncementView view);

  /** Edit the single latest announcement message's counts (only that message — FR-038). */
  void edit(long guildId, long channelId, long messageId, AnnouncementView view);
}
