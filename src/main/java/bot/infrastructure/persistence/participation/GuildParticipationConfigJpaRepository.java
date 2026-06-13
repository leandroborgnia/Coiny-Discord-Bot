package bot.infrastructure.persistence.participation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildParticipationConfigJpaRepository
    extends JpaRepository<GuildParticipationConfigEntity, Long> {}
