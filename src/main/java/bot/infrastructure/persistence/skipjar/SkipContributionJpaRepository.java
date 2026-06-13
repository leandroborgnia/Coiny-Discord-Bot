package bot.infrastructure.persistence.skipjar;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SkipContributionJpaRepository
    extends JpaRepository<SkipContributionEntity, SkipContributionId> {

  /** Jar count for one run — served by the PK's {@code (guild_id, week_number)} prefix. */
  int countByGuildIdAndWeekNumber(long guildId, int weekNumber);
}
