package bot.infrastructure.persistence.coin;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildCoinConfigJpaRepository extends JpaRepository<GuildCoinConfigEntity, Long> {}
