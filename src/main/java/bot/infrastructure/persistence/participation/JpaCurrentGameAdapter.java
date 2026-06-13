package bot.infrastructure.persistence.participation;

import bot.domain.participation.CurrentGamePort;
import bot.domain.queue.GameIdentity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * JPA/Postgres adapter reading the guild's current designated game by joining the rotation clock's
 * {@code current_slot_id} to the queue entry's {@code game_identity}. Empty when the guild has no
 * rotation row or no current slot — earning is then gated off for that guild (FR-011).
 */
@Component
public class JpaCurrentGameAdapter implements CurrentGamePort {

  @PersistenceContext private EntityManager entityManager;

  @Override
  public Optional<GameIdentity> currentGameIdentity(long guildId) {
    List<?> rows =
        entityManager
            .createNativeQuery(
                "SELECT qe.game_identity"
                    + " FROM queue_rotation_state rs"
                    + " JOIN queue_entry qe ON qe.id = rs.current_slot_id"
                    + " WHERE rs.guild_id = ?")
            .setParameter(1, guildId)
            .getResultList();
    if (rows.isEmpty() || rows.get(0) == null) {
      return Optional.empty();
    }
    return Optional.of(new GameIdentity((String) rows.get(0)));
  }
}
