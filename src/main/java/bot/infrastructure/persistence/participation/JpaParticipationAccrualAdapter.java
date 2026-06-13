package bot.infrastructure.persistence.participation;

import bot.domain.participation.ParticipationAccrual;
import bot.domain.participation.ParticipationAccrualPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * JPA/Postgres adapter for per-member accrual state. Called inside the application's transaction
 * (under the per-account advisory lock): the upsert is a native {@code ON CONFLICT DO UPDATE} so
 * the consumed-seconds decrement and {@code last_sampled_at} advance commit atomically with the
 * ledger append (the at-most-once guard). Synthetic drop ids come from a dedicated sequence,
 * negated so they never collide with positive Discord snowflakes.
 */
@Component
public class JpaParticipationAccrualAdapter implements ParticipationAccrualPort {

  @PersistenceContext private EntityManager entityManager;

  private final ParticipationAccrualJpaRepository repository;

  public JpaParticipationAccrualAdapter(ParticipationAccrualJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public ParticipationAccrual get(long guildId, long memberId) {
    return repository
        .findById(new ParticipationAccrualId(guildId, memberId))
        .map(
            e ->
                new ParticipationAccrual(
                    e.getGuildId(),
                    e.getMemberId(),
                    e.getBankedSeconds(),
                    e.getLastSampledAt() == null ? null : e.getLastSampledAt().toInstant()))
        .orElseGet(() -> new ParticipationAccrual(guildId, memberId, 0, null));
  }

  @Override
  public void upsert(long guildId, long memberId, long bankedSeconds, Instant lastSampledAt) {
    entityManager
        .createNativeQuery(
            "INSERT INTO participation_accrual"
                + " (guild_id, member_id, banked_seconds, last_sampled_at, updated_at)"
                + " VALUES (?, ?, ?, CAST(? AS timestamptz), now())"
                + " ON CONFLICT (guild_id, member_id) DO UPDATE"
                + " SET banked_seconds = EXCLUDED.banked_seconds,"
                + " last_sampled_at = EXCLUDED.last_sampled_at,"
                + " updated_at = now()")
        .setParameter(1, guildId)
        .setParameter(2, memberId)
        .setParameter(3, bankedSeconds)
        .setParameter(4, lastSampledAt == null ? null : lastSampledAt.toString())
        .executeUpdate();
  }

  @Override
  public long nextDropId() {
    Number next =
        (Number)
            entityManager
                .createNativeQuery("SELECT nextval('participation_drop_seq')")
                .getSingleResult();
    return -next.longValue();
  }
}
