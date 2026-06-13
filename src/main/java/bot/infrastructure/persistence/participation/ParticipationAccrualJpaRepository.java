package bot.infrastructure.persistence.participation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipationAccrualJpaRepository
    extends JpaRepository<ParticipationAccrualEntity, ParticipationAccrualId> {}
