package bot.infrastructure.persistence.queue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueEntryJpaRepository extends JpaRepository<QueueEntryEntity, Long> {}
