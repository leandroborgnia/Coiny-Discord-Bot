package bot.infrastructure.persistence.participation;

import bot.domain.participation.DesignatedChannelPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * JPA/Postgres adapter for the per-server designated-voice-channel set. {@code add} is idempotent
 * via {@code ON CONFLICT DO NOTHING}; {@code resetAll} deletes the guild's rows.
 */
@Component
public class JpaDesignatedChannelAdapter implements DesignatedChannelPort {

  @PersistenceContext private EntityManager entityManager;

  private final ParticipationVoiceChannelJpaRepository repository;

  public JpaDesignatedChannelAdapter(ParticipationVoiceChannelJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public void add(long guildId, long channelId) {
    entityManager
        .createNativeQuery(
            "INSERT INTO participation_voice_channel (guild_id, channel_id)"
                + " VALUES (?, ?)"
                + " ON CONFLICT (guild_id, channel_id) DO NOTHING")
        .setParameter(1, guildId)
        .setParameter(2, channelId)
        .executeUpdate();
  }

  @Override
  public void resetAll(long guildId) {
    repository.deleteByGuildId(guildId);
  }

  @Override
  public List<Long> list(long guildId) {
    return repository.findByGuildId(guildId).stream()
        .map(ParticipationVoiceChannelEntity::getChannelId)
        .toList();
  }

  @Override
  public boolean contains(long guildId, long channelId) {
    return repository.existsById(new ParticipationVoiceChannelId(guildId, channelId));
  }

  @Override
  public List<Long> guildsWithChannels() {
    return repository.findDistinctGuildIds();
  }
}
