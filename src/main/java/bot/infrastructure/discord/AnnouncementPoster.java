package bot.infrastructure.discord;

import bot.domain.queue.AnnouncementPort;
import bot.domain.queue.AnnouncementView;
import bot.domain.queue.QueueConfigPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Posts a prepared announcement to a guild's configured channel and records it as the latest live
 * surface (FR-036/FR-038) — shared by the weekly advance (scheduler) and the bootstrap instant-pop
 * (propose command), so both designate-the-current-game paths announce identically. A no-op when no
 * announcement channel is configured (announcements are opt-in). Runs after the transaction
 * commits, off the interaction ack (Principle V). Gated by {@code discord.enabled}.
 */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AnnouncementPoster {

  private final QueueConfigPort configPort;
  private final AnnouncementPort announcementPort;

  public AnnouncementPoster(QueueConfigPort configPort, AnnouncementPort announcementPort) {
    this.configPort = configPort;
    this.announcementPort = announcementPort;
  }

  public void post(long guildId, AnnouncementView view) {
    configPort
        .get(guildId)
        .announcementChannel()
        .ifPresent(
            channelId -> {
              long messageId = announcementPort.post(guildId, channelId, view);
              configPort.setLatestAnnouncement(guildId, channelId, messageId);
            });
  }
}
