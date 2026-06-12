package bot.infrastructure.persistence.queue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueRotationStateJpaRepository
    extends JpaRepository<QueueRotationStateEntity, Long> {}
