package bot.infrastructure.persistence.participation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ParticipationVoiceChannelJpaRepository
    extends JpaRepository<ParticipationVoiceChannelEntity, ParticipationVoiceChannelId> {

  List<ParticipationVoiceChannelEntity> findByGuildId(long guildId);

  @Transactional
  void deleteByGuildId(long guildId);

  @Query("SELECT DISTINCT e.guildId FROM ParticipationVoiceChannelEntity e")
  List<Long> findDistinctGuildIds();
}
