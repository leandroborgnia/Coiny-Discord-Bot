package bot.application.participation;

import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.ModeratorNotAuthorizedException;
import bot.domain.coin.ModeratorRoleNotConfiguredException;
import bot.domain.participation.DesignatedChannelPort;
import bot.domain.participation.GuildParticipationConfig;
import bot.domain.participation.ParticipationConfigPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The participation-config write path (US2 + rate). Opens the single transaction, authorizes
 * against the server's configured coin-moderator role exactly like {@code AdjustCoinsService}
 * (Administrator bypasses; fails closed when no role is configured), dispatches the requested
 * change, and returns the re-read effective configuration. Unauthorized requests change nothing
 * (SC-008).
 */
@Service
public class ConfigureParticipationService {

  private final GuildCoinConfigPort guildCoinConfigPort;
  private final ParticipationConfigPort participationConfigPort;
  private final DesignatedChannelPort designatedChannelPort;

  public ConfigureParticipationService(
      GuildCoinConfigPort guildCoinConfigPort,
      ParticipationConfigPort participationConfigPort,
      DesignatedChannelPort designatedChannelPort) {
    this.guildCoinConfigPort = guildCoinConfigPort;
    this.participationConfigPort = participationConfigPort;
    this.designatedChannelPort = designatedChannelPort;
  }

  @Transactional
  public ParticipationConfigResult configure(ConfigureParticipationRequest request) {
    authorize(request);

    long guildId = request.guildId();
    switch (request.op()) {
      case CHANNEL_ADD -> designatedChannelPort.add(guildId, request.channelId());
      case CHANNEL_RESET -> designatedChannelPort.resetAll(guildId);
      case RATE -> {
        int minutes = request.minutesPerDrop();
        int coins = request.coinsPerDrop();
        if (minutes < 1 || coins < 1) { // defensive re-validation (the command sets min 1)
          throw new IllegalArgumentException("rate values must be >= 1");
        }
        participationConfigPort.setRate(guildId, minutes, coins);
      }
      case FREE_PROPOSAL ->
          participationConfigPort.setFreeFirstProposal(guildId, request.freeFirstProposal());
    }

    return currentConfig(guildId);
  }

  private void authorize(ConfigureParticipationRequest request) {
    GuildCoinConfig config = guildCoinConfigPort.get(request.guildId());
    if (!config.hasModeratorRole()) {
      throw new ModeratorRoleNotConfiguredException();
    }
    boolean hasRole = request.actorRoleIds().contains(config.moderatorRoleId());
    if (!request.actorIsAdmin() && !hasRole) {
      throw ModeratorNotAuthorizedException.missingRole();
    }
  }

  private ParticipationConfigResult currentConfig(long guildId) {
    GuildParticipationConfig config = participationConfigPort.get(guildId);
    int channelCount = designatedChannelPort.list(guildId).size();
    return new ParticipationConfigResult(
        channelCount,
        config.rate().minutesPerDrop(),
        config.rate().coinsPerDrop(),
        config.freeFirstProposal());
  }
}
