package bot.infrastructure.persistence.queue;

import bot.domain.queue.UpvotePort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * JPA adapter for per-slot upvotes. The toggle inserts {@code ON CONFLICT DO NOTHING} (a no-op if
 * already present) and otherwise deletes, so it is idempotent across multiple ephemeral renders;
 * the count is scoped to a slot's current appearance ({@code game_instance_id}) so a replace shows
 * zero.
 */
@Component
public class JpaUpvoteAdapter implements UpvotePort {

  @PersistenceContext private EntityManager entityManager;

  @Override
  public boolean toggle(long slotId, long memberId, UUID gameInstanceId) {
    int inserted =
        entityManager
            .createNativeQuery(
                "INSERT INTO queue_upvote (slot_id, member_id, game_instance_id)"
                    + " VALUES (?, ?, CAST(? AS uuid)) ON CONFLICT DO NOTHING")
            .setParameter(1, slotId)
            .setParameter(2, memberId)
            .setParameter(3, gameInstanceId.toString())
            .executeUpdate();
    if (inserted == 1) {
      return true; // upvote added
    }
    entityManager
        .createNativeQuery(
            "DELETE FROM queue_upvote"
                + " WHERE slot_id = ? AND member_id = ? AND game_instance_id = CAST(? AS uuid)")
        .setParameter(1, slotId)
        .setParameter(2, memberId)
        .setParameter(3, gameInstanceId.toString())
        .executeUpdate();
    return true; // upvote removed (toggle off)
  }

  @Override
  public int count(long slotId, UUID gameInstanceId) {
    Object count =
        entityManager
            .createNativeQuery(
                "SELECT count(*) FROM queue_upvote"
                    + " WHERE slot_id = ? AND game_instance_id = CAST(? AS uuid)")
            .setParameter(1, slotId)
            .setParameter(2, gameInstanceId.toString())
            .getSingleResult();
    return ((Number) count).intValue();
  }

  @Override
  public void resetForSlot(long slotId) {
    entityManager
        .createNativeQuery("DELETE FROM queue_upvote WHERE slot_id = ?")
        .setParameter(1, slotId)
        .executeUpdate();
  }
}
