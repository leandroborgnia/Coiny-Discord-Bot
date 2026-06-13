package bot.infrastructure.persistence.skipjar;

import bot.domain.skipjar.SkipContributionPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * JPA/Postgres adapter for the per-run skip-contribution log. Called inside the contribution
 * transaction (under the per-guild queue advisory lock): {@code record} is a native INSERT — no
 * {@code ON CONFLICT}, so a duplicate {@code (guild_id, week_number, member_id)} raises a unique
 * violation that rolls back the coin debit (the once-per-run backstop, FR-002). The count is served
 * by the PK's {@code (guild_id, week_number)} prefix.
 */
@Component
public class JpaSkipContributionAdapter implements SkipContributionPort {

  @PersistenceContext private EntityManager entityManager;

  private final SkipContributionJpaRepository repository;

  public JpaSkipContributionAdapter(SkipContributionJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean hasContributed(long guildId, int weekNumber, long memberId) {
    return repository.existsById(new SkipContributionId(guildId, weekNumber, memberId));
  }

  @Override
  public int count(long guildId, int weekNumber) {
    return repository.countByGuildIdAndWeekNumber(guildId, weekNumber);
  }

  @Override
  public void record(long guildId, int weekNumber, long memberId, long movementId) {
    entityManager
        .createNativeQuery(
            "INSERT INTO skip_contribution"
                + " (guild_id, week_number, member_id, movement_id, created_at)"
                + " VALUES (?, ?, ?, ?, now())")
        .setParameter(1, guildId)
        .setParameter(2, weekNumber)
        .setParameter(3, memberId)
        .setParameter(4, movementId)
        .executeUpdate();
  }
}
