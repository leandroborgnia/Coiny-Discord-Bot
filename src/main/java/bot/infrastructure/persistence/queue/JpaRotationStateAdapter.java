package bot.infrastructure.persistence.queue;

import bot.domain.queue.GameIdentity;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/** JPA adapter for the per-guild rotation clock and the append-only weekly-designation log. */
@Component
public class JpaRotationStateAdapter implements RotationStatePort {

  @PersistenceContext private EntityManager entityManager;

  private final QueueRotationStateJpaRepository repository;

  public JpaRotationStateAdapter(QueueRotationStateJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public RotationState get(long guildId) {
    return repository
        .findById(guildId)
        .map(JpaRotationStateAdapter::toState)
        .orElseGet(() -> new RotationState(guildId, null, 0, null));
  }

  @Override
  public List<Long> guildsWithState() {
    return repository.findAll().stream().map(QueueRotationStateEntity::getGuildId).toList();
  }

  @Override
  public void bootstrap(long guildId, long slotId, Instant at) {
    QueueRotationStateEntity entity =
        repository.findById(guildId).orElseGet(() -> new QueueRotationStateEntity(guildId));
    entity.setCurrentSlotId(slotId);
    entity.setLastPopAt(OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    entity.touch();
    repository.save(entity);
  }

  @Override
  public void recordDesignation(
      long guildId, int week, Long slotId, GameIdentity identity, Instant at) {
    entityManager
        .createNativeQuery(
            "INSERT INTO weekly_designation"
                + " (guild_id, week_number, slot_id, game_identity, game_name, designated_at)"
                + " VALUES (?, ?, CAST(? AS bigint), ?, ?, CAST(? AS timestamptz))"
                + " ON CONFLICT (guild_id, week_number) DO NOTHING")
        .setParameter(1, guildId)
        .setParameter(2, week)
        .setParameter(3, slotId)
        .setParameter(4, identity == null ? null : identity.value())
        .setParameter(5, null)
        .setParameter(6, at.toString())
        .executeUpdate();
  }

  @Override
  public void advanceClock(long guildId, Long currentSlotId, int week, Instant lastPopAt) {
    QueueRotationStateEntity entity =
        repository.findById(guildId).orElseGet(() -> new QueueRotationStateEntity(guildId));
    entity.setCurrentSlotId(currentSlotId);
    entity.setCurrentWeekNumber(week);
    entity.setLastPopAt(OffsetDateTime.ofInstant(lastPopAt, ZoneOffset.UTC));
    entity.touch();
    repository.save(entity);
  }

  private static RotationState toState(QueueRotationStateEntity e) {
    return new RotationState(
        e.getGuildId(),
        e.getCurrentSlotId(),
        e.getCurrentWeekNumber(),
        e.getLastPopAt() == null ? null : e.getLastPopAt().toInstant());
  }
}
