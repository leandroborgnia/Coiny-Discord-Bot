package bot.infrastructure.persistence.queue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildQueueConfigJpaRepository
    extends JpaRepository<GuildQueueConfigEntity, Long> {}
