package bot.infrastructure.persistence.queue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueCooldownJpaRepository
    extends JpaRepository<QueueCooldownEntity, QueueCooldownId> {}
