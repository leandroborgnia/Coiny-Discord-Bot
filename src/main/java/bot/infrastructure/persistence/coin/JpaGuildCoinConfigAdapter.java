package bot.infrastructure.persistence.coin;

import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import org.springframework.stereotype.Component;

/** JPA adapter for per-server coin configuration; absent servers default to cap 12, no role. */
@Component
public class JpaGuildCoinConfigAdapter implements GuildCoinConfigPort {

  /** Out-of-the-box cap applied until a server changes it (spec FR-007). */
  static final int DEFAULT_CAP = 12;

  private final GuildCoinConfigJpaRepository repository;

  public JpaGuildCoinConfigAdapter(GuildCoinConfigJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public GuildCoinConfig get(long guildId) {
    return repository
        .findById(guildId)
        .map(e -> new GuildCoinConfig(e.getGuildId(), e.getModeratorRoleId(), e.getCoinCap()))
        .orElseGet(() -> new GuildCoinConfig(guildId, null, DEFAULT_CAP));
  }

  @Override
  public GuildCoinConfig upsert(long guildId, Long moderatorRoleId, Integer cap) {
    GuildCoinConfigEntity entity =
        repository
            .findById(guildId)
            .orElseGet(() -> new GuildCoinConfigEntity(guildId, null, DEFAULT_CAP));
    if (moderatorRoleId != null) {
      entity.setModeratorRoleId(moderatorRoleId);
    }
    if (cap != null) {
      entity.setCoinCap(cap);
    }
    entity.touch();
    GuildCoinConfigEntity saved = repository.save(entity);
    return new GuildCoinConfig(saved.getGuildId(), saved.getModeratorRoleId(), saved.getCoinCap());
  }
}
