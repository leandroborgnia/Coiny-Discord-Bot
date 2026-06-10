package bot.application.coin;

import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.InvalidCoinCapException;
import bot.domain.coin.ModeratorNotAuthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configures a server's moderator role and/or balance cap. Requires the caller to be a server
 * administrator; a null role or cap leaves that setting unchanged.
 */
@Service
public class CoinConfigService {

  private final GuildCoinConfigPort configPort;

  public CoinConfigService(GuildCoinConfigPort configPort) {
    this.configPort = configPort;
  }

  @Transactional
  public CoinConfigResult configure(ConfigureCoinsRequest request) {
    if (!request.actorIsAdmin()) {
      throw ModeratorNotAuthorizedException.notAdmin();
    }
    if (request.cap() != null && request.cap() < 0) {
      throw new InvalidCoinCapException(request.cap());
    }
    GuildCoinConfig updated =
        configPort.upsert(request.guildId(), request.moderatorRoleId(), request.cap());
    return new CoinConfigResult(updated.moderatorRoleId(), updated.cap());
  }
}
