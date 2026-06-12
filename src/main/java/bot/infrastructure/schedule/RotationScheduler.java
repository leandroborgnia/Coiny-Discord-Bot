package bot.infrastructure.schedule;

import bot.application.queue.AdvanceResult;
import bot.application.queue.AdvanceRotationService;
import bot.domain.queue.AnnouncementPort;
import bot.domain.queue.AnnouncementView;
import bot.domain.queue.QueueConfigPort;
import bot.domain.queue.RotationStatePort;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the rolling-7-day rotation (US2). A fixed-delay tick (resolution from {@code
 * queue.rotation.tick}) and a one-shot startup catch-up both apply every due advance per guild,
 * then post the single final announcement off the committed transaction (Principle V). The
 * per-guild advance is called through the {@link AdvanceRotationService} bean so each runs in its
 * own transaction. Gated by {@code discord.enabled}, so tests never schedule and never need JDA.
 */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RotationScheduler {

  private static final Logger log = LoggerFactory.getLogger(RotationScheduler.class);

  private final AdvanceRotationService advanceRotationService;
  private final RotationStatePort rotationStatePort;
  private final QueueConfigPort configPort;
  private final AnnouncementPort announcementPort;

  public RotationScheduler(
      AdvanceRotationService advanceRotationService,
      RotationStatePort rotationStatePort,
      QueueConfigPort configPort,
      AnnouncementPort announcementPort) {
    this.advanceRotationService = advanceRotationService;
    this.rotationStatePort = rotationStatePort;
    this.configPort = configPort;
    this.announcementPort = announcementPort;
  }

  @Scheduled(fixedDelayString = "${queue.rotation.tick}")
  public void tick() {
    catchUp();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void catchUpOnStartup() {
    catchUp();
  }

  private void catchUp() {
    Instant now = Instant.now();
    for (long guildId : rotationStatePort.guildsWithState()) {
      try {
        AdvanceResult result = advanceRotationService.advanceDue(guildId, now);
        result.finalAnnouncement().ifPresent(view -> postAnnouncement(guildId, view));
      } catch (RuntimeException e) {
        log.warn("Rotation advance failed for guild {}: {}", guildId, e.toString());
      }
    }
  }

  private void postAnnouncement(long guildId, AnnouncementView view) {
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
