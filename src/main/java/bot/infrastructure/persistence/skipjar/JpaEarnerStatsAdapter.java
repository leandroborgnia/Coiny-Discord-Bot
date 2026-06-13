package bot.infrastructure.persistence.skipjar;

import bot.domain.skipjar.EarnerStatsPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * JPA/Postgres adapter for the distinct-earner set of the current run, read from the existing
 * participation ledger (no new column). An earner is a member with a {@code PARTICIPATION} movement
 * crediting ≥ 1 coin since the run boundary; {@code credited_amount > 0} enforces "a coin actually
 * landed" (a fully over-cap drop does not make an earner). The participation sweep only credits the
 * current game, so every such movement after {@code since} belongs to the current run (D-2).
 */
@Component
public class JpaEarnerStatsAdapter implements EarnerStatsPort {

  @PersistenceContext private EntityManager entityManager;

  @Override
  public int distinctEarnerCount(long guildId, Instant since) {
    Number count =
        (Number)
            entityManager
                .createNativeQuery(
                    "SELECT COUNT(DISTINCT member_id) FROM coin_movement"
                        + " WHERE guild_id = ? AND type = 'PARTICIPATION'"
                        + " AND credited_amount > 0 AND created_at >= CAST(? AS timestamptz)")
                .setParameter(1, guildId)
                .setParameter(2, since.toString())
                .getSingleResult();
    return count.intValue();
  }

  @Override
  public boolean isEarner(long guildId, long memberId, Instant since) {
    Boolean exists =
        (Boolean)
            entityManager
                .createNativeQuery(
                    "SELECT EXISTS (SELECT 1 FROM coin_movement"
                        + " WHERE guild_id = ? AND member_id = ? AND type = 'PARTICIPATION'"
                        + " AND credited_amount > 0 AND created_at >= CAST(? AS timestamptz))")
                .setParameter(1, guildId)
                .setParameter(2, memberId)
                .setParameter(3, since.toString())
                .getSingleResult();
    return Boolean.TRUE.equals(exists);
  }
}
