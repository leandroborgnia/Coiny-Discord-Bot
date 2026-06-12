package bot.infrastructure.persistence.queue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GameArtCacheJpaRepository extends JpaRepository<GameArtCacheEntity, String> {}
