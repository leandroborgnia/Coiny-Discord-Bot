package bot.application.skipjar;

import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.ModeratorNotAuthorizedException;
import bot.domain.coin.ModeratorRoleNotConfiguredException;
import bot.domain.skipjar.GuildSkipJarConfig;
import bot.domain.skipjar.SkipJarConfigPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The skip-jar-config write path (US4). Opens the single transaction, authorizes against the
 * server's configured coin-moderator role exactly like {@code ConfigureParticipationService}
 * (Administrator bypasses; fails closed when no role is configured), dispatches the requested
 * change with defensive re-validation, and returns the re-read effective configuration.
 * Unauthorized requests change nothing (FR-017 / SC-009).
 */
@Service
public class ConfigureSkipJarService {

  private final GuildCoinConfigPort guildCoinConfigPort;
  private final SkipJarConfigPort skipJarConfigPort;

  public ConfigureSkipJarService(
      GuildCoinConfigPort guildCoinConfigPort, SkipJarConfigPort skipJarConfigPort) {
    this.guildCoinConfigPort = guildCoinConfigPort;
    this.skipJarConfigPort = skipJarConfigPort;
  }

  @Transactional
  public SkipJarConfigResult configure(ConfigureRequest request) {
    authorize(request);

    long guildId = request.guildId();
    switch (request.op()) {
      case FLOOR -> {
        if (request.floor() < 1) { // defensive re-validation (the command sets min 1)
          throw new IllegalArgumentException("threshold floor must be >= 1");
        }
        skipJarConfigPort.setFloor(guildId, request.floor());
      }
      case DWELL -> {
        if (request.dwellSeconds() < 1) { // defensive re-validation (the command sets min > 0)
          throw new IllegalArgumentException("dwell seconds must be >= 1");
        }
        skipJarConfigPort.setDwell(guildId, request.dwellSeconds());
      }
      case GATE -> skipJarConfigPort.setGate(guildId, request.gateOn());
    }

    GuildSkipJarConfig effective = skipJarConfigPort.get(guildId);
    return new SkipJarConfigResult(
        effective.thresholdFloor(), effective.dwell().toSeconds(), effective.gateOn());
  }

  private void authorize(ConfigureRequest request) {
    GuildCoinConfig config = guildCoinConfigPort.get(request.guildId());
    if (!config.hasModeratorRole()) {
      throw new ModeratorRoleNotConfiguredException();
    }
    boolean hasRole = request.actorRoleIds().contains(config.moderatorRoleId());
    if (!request.actorIsAdmin() && !hasRole) {
      throw ModeratorNotAuthorizedException.missingRole();
    }
  }
}
