package bot.infrastructure.persistence.skipjar;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildSkipJarConfigJpaRepository
    extends JpaRepository<GuildSkipJarConfigEntity, Long> {}
