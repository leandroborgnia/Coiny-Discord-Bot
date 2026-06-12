package bot.infrastructure.persistence.queue;

import bot.domain.queue.CooldownPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/** JPA adapter for the "wait N games" cooldown; absent rows mean no cooldown (0 remaining). */
@Component
public class JpaCooldownAdapter implements CooldownPort {

  @PersistenceContext private EntityManager entityManager;

  private final QueueCooldownJpaRepository repository;

  public JpaCooldownAdapter(QueueCooldownJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public int gamesRemaining(long guildId, long memberId) {
    return repository
        .findById(new QueueCooldownId(guildId, memberId))
        .map(QueueCooldownEntity::getGamesRemaining)
        .orElse(0);
  }

  @Override
  public void set(long guildId, long memberId, int n) {
    QueueCooldownEntity entity =
        repository
            .findById(new QueueCooldownId(guildId, memberId))
            .orElseGet(() -> new QueueCooldownEntity(guildId, memberId, n));
    entity.setGamesRemaining(n);
    repository.save(entity);
  }

  @Override
  public void decrementAll(long guildId) {
    entityManager
        .createNativeQuery(
            "UPDATE queue_cooldown SET games_remaining = GREATEST(0, games_remaining - 1)"
                + " WHERE guild_id = ?")
        .setParameter(1, guildId)
        .executeUpdate();
  }
}
