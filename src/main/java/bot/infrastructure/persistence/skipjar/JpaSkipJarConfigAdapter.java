package bot.infrastructure.persistence.skipjar;

import bot.domain.skipjar.GuildSkipJarConfig;
import bot.domain.skipjar.SkipJarConfigPort;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * JPA adapter for per-server skip-jar configuration; an absent row reads as {@link
 * GuildSkipJarConfig#defaults(long)} (floor 3, 24 h dwell, gate on). Setters upsert by PK, seeding
 * the other fields from the defaults when the row does not yet exist.
 */
@Component
public class JpaSkipJarConfigAdapter implements SkipJarConfigPort {

  private final GuildSkipJarConfigJpaRepository repository;

  public JpaSkipJarConfigAdapter(GuildSkipJarConfigJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public GuildSkipJarConfig get(long guildId) {
    return repository
        .findById(guildId)
        .map(JpaSkipJarConfigAdapter::toConfig)
        .orElseGet(() -> GuildSkipJarConfig.defaults(guildId));
  }

  @Override
  public void setFloor(long guildId, int thresholdFloor) {
    GuildSkipJarConfigEntity entity = findOrNew(guildId);
    entity.setThresholdFloor(thresholdFloor);
    entity.touch();
    repository.save(entity);
  }

  @Override
  public void setDwell(long guildId, long dwellSeconds) {
    GuildSkipJarConfigEntity entity = findOrNew(guildId);
    entity.setDwellSeconds(dwellSeconds);
    entity.touch();
    repository.save(entity);
  }

  @Override
  public void setGate(long guildId, boolean gateOn) {
    GuildSkipJarConfigEntity entity = findOrNew(guildId);
    entity.setParticipationGate(gateOn);
    entity.touch();
    repository.save(entity);
  }

  private GuildSkipJarConfigEntity findOrNew(long guildId) {
    GuildSkipJarConfig defaults = GuildSkipJarConfig.defaults(guildId);
    return repository
        .findById(guildId)
        .orElseGet(
            () ->
                new GuildSkipJarConfigEntity(
                    guildId,
                    defaults.thresholdFloor(),
                    defaults.dwell().toSeconds(),
                    defaults.gateOn()));
  }

  private static GuildSkipJarConfig toConfig(GuildSkipJarConfigEntity e) {
    return new GuildSkipJarConfig(
        e.getGuildId(),
        e.getThresholdFloor(),
        Duration.ofSeconds(e.getDwellSeconds()),
        e.isParticipationGate());
  }
}
