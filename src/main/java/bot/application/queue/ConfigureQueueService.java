package bot.application.queue;

import bot.application.queue.ConfigureQueueRequest.ChannelOp;
import bot.domain.queue.GuildQueueConfig;
import bot.domain.queue.NotAuthorizedException;
import bot.domain.queue.QueueConfigPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configures a server's queue costs and announcement channel (FR-017/018/037). Authorized on the
 * **Manage Server** permission alone — the single bar, matching the command's {@code
 * DefaultMemberPermissions} filter (no coin-moderator-role coupling, no role-not-configured
 * precondition). Changes apply to subsequent actions only.
 */
@Service
public class ConfigureQueueService {

  private final QueueConfigPort configPort;

  public ConfigureQueueService(QueueConfigPort configPort) {
    this.configPort = configPort;
  }

  @Transactional
  public QueueConfigResult configure(ConfigureQueueRequest request) {
    if (!request.actorHasManageServer()) {
      throw new NotAuthorizedException();
    }
    validateCost(request.proposeCost());
    validateCost(request.bumpCost());

    if (request.proposeCost() != null || request.bumpCost() != null) {
      configPort.upsertCosts(request.guildId(), request.proposeCost(), request.bumpCost());
    }
    ChannelOp announcement = request.announcement();
    if (announcement != null) {
      configPort.setAnnouncementChannel(
          request.guildId(), announcement.clear() ? null : announcement.channelId());
    }

    GuildQueueConfig config = configPort.get(request.guildId());
    return new QueueConfigResult(
        config.proposeCost(), config.bumpCost(), config.announcementChannelId());
  }

  private static void validateCost(Integer cost) {
    if (cost != null && cost < 1) {
      throw new IllegalArgumentException("queue cost must be at least 1 (was " + cost + ")");
    }
  }
}
