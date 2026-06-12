package bot.infrastructure.persistence.queue;

import bot.domain.queue.GuildQueueConfig;
import bot.domain.queue.QueueConfigPort;
import org.springframework.stereotype.Component;

/** JPA adapter for per-server queue configuration; absent servers default to propose=1, bump=1. */
@Component
public class JpaQueueConfigAdapter implements QueueConfigPort {

  private final GuildQueueConfigJpaRepository repository;

  public JpaQueueConfigAdapter(GuildQueueConfigJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public GuildQueueConfig get(long guildId) {
    return repository
        .findById(guildId)
        .map(JpaQueueConfigAdapter::toConfig)
        .orElseGet(() -> GuildQueueConfig.defaults(guildId));
  }

  @Override
  public void upsertCosts(long guildId, Integer proposeCost, Integer bumpCost) {
    GuildQueueConfigEntity entity = findOrNew(guildId);
    if (proposeCost != null) {
      entity.setProposeCost(proposeCost);
    }
    if (bumpCost != null) {
      entity.setBumpCost(bumpCost);
    }
    entity.touch();
    repository.save(entity);
  }

  @Override
  public void setAnnouncementChannel(long guildId, Long channelId) {
    GuildQueueConfigEntity entity = findOrNew(guildId);
    entity.setAnnouncementChannelId(channelId); // null clears
    entity.touch();
    repository.save(entity);
  }

  @Override
  public void setLatestAnnouncement(long guildId, long channelId, long messageId) {
    GuildQueueConfigEntity entity = findOrNew(guildId);
    entity.setLatestAnnouncementChannelId(channelId);
    entity.setLatestAnnouncementMessageId(messageId);
    entity.touch();
    repository.save(entity);
  }

  private GuildQueueConfigEntity findOrNew(long guildId) {
    return repository
        .findById(guildId)
        .orElseGet(
            () ->
                new GuildQueueConfigEntity(
                    guildId,
                    GuildQueueConfig.DEFAULT_PROPOSE_COST,
                    GuildQueueConfig.DEFAULT_BUMP_COST));
  }

  private static GuildQueueConfig toConfig(GuildQueueConfigEntity e) {
    return new GuildQueueConfig(
        e.getGuildId(),
        e.getProposeCost(),
        e.getBumpCost(),
        e.getAnnouncementChannelId(),
        e.getLatestAnnouncementChannelId(),
        e.getLatestAnnouncementMessageId());
  }
}
