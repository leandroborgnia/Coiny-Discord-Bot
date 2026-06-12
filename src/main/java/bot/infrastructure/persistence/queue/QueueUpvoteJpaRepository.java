package bot.infrastructure.persistence.queue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueUpvoteJpaRepository extends JpaRepository<QueueUpvoteEntity, QueueUpvoteId> {}
