package bot.infrastructure.persistence.participation;

import bot.domain.participation.GuildParticipationConfig;
import bot.domain.participation.ParticipationConfigPort;
import bot.domain.participation.ParticipationRate;
import org.springframework.stereotype.Component;

/**
 * JPA adapter for per-server participation configuration; absent servers default to rate 60/1 with
 * free-first-proposal off ({@link GuildParticipationConfig#defaults(long)}).
 */
@Component
public class JpaParticipationConfigAdapter implements ParticipationConfigPort {

  private final GuildParticipationConfigJpaRepository repository;

  public JpaParticipationConfigAdapter(GuildParticipationConfigJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public GuildParticipationConfig get(long guildId) {
    return repository
        .findById(guildId)
        .map(JpaParticipationConfigAdapter::toConfig)
        .orElseGet(() -> GuildParticipationConfig.defaults(guildId));
  }

  @Override
  public boolean freeFirstProposalEnabled(long guildId) {
    return repository
        .findById(guildId)
        .map(GuildParticipationConfigEntity::isFreeFirstProposal)
        .orElse(false);
  }

  @Override
  public void setRate(long guildId, int minutesPerDrop, int coinsPerDrop) {
    GuildParticipationConfigEntity entity = findOrNew(guildId);
    entity.setMinutesPerDrop(minutesPerDrop);
    entity.setCoinsPerDrop(coinsPerDrop);
    entity.touch();
    repository.save(entity);
  }

  @Override
  public void setFreeFirstProposal(long guildId, boolean enabled) {
    GuildParticipationConfigEntity entity = findOrNew(guildId);
    entity.setFreeFirstProposal(enabled);
    entity.touch();
    repository.save(entity);
  }

  private GuildParticipationConfigEntity findOrNew(long guildId) {
    ParticipationRate defaults = ParticipationRate.defaults();
    return repository
        .findById(guildId)
        .orElseGet(
            () ->
                new GuildParticipationConfigEntity(
                    guildId, defaults.minutesPerDrop(), defaults.coinsPerDrop(), false));
  }

  private static GuildParticipationConfig toConfig(GuildParticipationConfigEntity e) {
    return new GuildParticipationConfig(
        e.getGuildId(),
        new ParticipationRate(e.getMinutesPerDrop(), e.getCoinsPerDrop()),
        e.isFreeFirstProposal());
  }
}
