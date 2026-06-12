package bot.infrastructure.persistence.queue;

import bot.domain.queue.CapturedGame;
import bot.domain.queue.GameIdentity;
import bot.domain.queue.NewSlot;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.QueueStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * JPA/Postgres adapter for the per-guild queue. Called inside the application's transaction: the
 * advisory lock is transaction-scoped, inserts are idempotent via {@code ON CONFLICT
 * (propose_interaction_id) DO NOTHING}, and a removal re-denses positions in a two-step
 * negate-then-renumber so the partial unique index on {@code (guild_id, position)} is never
 * violated mid-statement. Leans on Postgres per Principles I & IV.
 */
@Component
public class JpaQueueAdapter implements QueuePort {

  private static final long QUEUE_LOCK_MIX =
      0xC2B2AE3D27D4EB4FL; // distinct from the account-lock mix
  private static final String QUEUED = QueueStatus.QUEUED.name();

  @PersistenceContext private EntityManager entityManager;

  private final QueueEntryJpaRepository repository;

  public JpaQueueAdapter(QueueEntryJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public void lockQueue(long guildId) {
    entityManager
        .createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
        .setParameter("key", guildId * QUEUE_LOCK_MIX)
        .getSingleResult();
  }

  @Override
  public List<QueueSlot> queued(long guildId) {
    return repository.findByGuildIdAndStatusOrderByPositionAsc(guildId, QUEUED).stream()
        .map(JpaQueueAdapter::toSlot)
        .toList();
  }

  @Override
  public Optional<QueueSlot> ownQueued(long guildId, long memberId) {
    return repository
        .findByGuildIdAndProposerMemberIdAndStatus(guildId, memberId, QUEUED)
        .map(JpaQueueAdapter::toSlot);
  }

  @Override
  public Optional<QueueSlot> findByProposeInteraction(long proposeInteractionId) {
    return repository.findByProposeInteractionId(proposeInteractionId).map(JpaQueueAdapter::toSlot);
  }

  @Override
  public Optional<QueueSlot> findSlot(long slotId) {
    return repository.findById(slotId).map(JpaQueueAdapter::toSlot);
  }

  @Override
  public QueueSlot append(NewSlot slot) {
    Query insert =
        entityManager
            .createNativeQuery(
                "INSERT INTO queue_entry"
                    + " (guild_id, proposer_member_id, status, position, game_identity,"
                    + " game_instance_id, game_name, application_id, rp_details, rp_state,"
                    + " rp_large_image, rp_small_image, snapshot, coins_spent,"
                    + " propose_interaction_id, played_week)"
                    + " VALUES (?, ?, ?, CAST(? AS int), ?, CAST(? AS uuid), ?, CAST(? AS bigint),"
                    + " ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS int))"
                    + " ON CONFLICT (propose_interaction_id) DO NOTHING"
                    + " RETURNING id")
            .setParameter(1, slot.guildId())
            .setParameter(2, slot.proposerMemberId())
            .setParameter(3, slot.status().name())
            .setParameter(4, slot.position())
            .setParameter(5, slot.identity().value())
            .setParameter(6, slot.gameInstanceId().toString())
            .setParameter(7, slot.game().name())
            .setParameter(8, slot.game().applicationId())
            .setParameter(9, slot.game().details())
            .setParameter(10, slot.game().state())
            .setParameter(11, slot.game().largeImageUrl())
            .setParameter(12, slot.game().smallImageUrl())
            .setParameter(13, slot.game().rawJson())
            .setParameter(14, slot.coinsSpent())
            .setParameter(15, slot.proposeInteractionId())
            .setParameter(16, slot.playedWeek());

    List<?> ids = insert.getResultList();
    if (ids.isEmpty()) {
      // A duplicate interaction won the race; return the originally recorded slot.
      return findByProposeInteraction(slot.proposeInteractionId())
          .orElseThrow(
              () -> new IllegalStateException("idempotent insert no-op but no existing slot"));
    }
    long id = ((Number) ids.get(0)).longValue();
    return new QueueSlot(
        id,
        slot.guildId(),
        slot.proposerMemberId(),
        slot.game(),
        slot.identity(),
        slot.gameInstanceId(),
        slot.position(),
        slot.status(),
        slot.coinsSpent(),
        0,
        slot.playedWeek());
  }

  @Override
  public void replaceGame(
      long slotId, CapturedGame game, GameIdentity identity, UUID newInstanceId) {
    entityManager
        .createNativeQuery(
            "UPDATE queue_entry SET game_identity = ?, game_instance_id = CAST(? AS uuid),"
                + " game_name = ?, application_id = CAST(? AS bigint), rp_details = ?, rp_state = ?,"
                + " rp_large_image = ?, rp_small_image = ?, snapshot = CAST(? AS jsonb)"
                + " WHERE id = ? AND status = 'QUEUED'")
        .setParameter(1, identity.value())
        .setParameter(2, newInstanceId.toString())
        .setParameter(3, game.name())
        .setParameter(4, game.applicationId())
        .setParameter(5, game.details())
        .setParameter(6, game.state())
        .setParameter(7, game.largeImageUrl())
        .setParameter(8, game.smallImageUrl())
        .setParameter(9, game.rawJson())
        .setParameter(10, slotId)
        .executeUpdate();
  }

  @Override
  public Optional<UUID> currentInstance(long slotId) {
    List<?> rows =
        entityManager
            .createNativeQuery(
                "SELECT game_instance_id FROM queue_entry WHERE id = ? AND status = 'QUEUED'")
            .setParameter(1, slotId)
            .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object value = rows.get(0);
    return Optional.of(value instanceof UUID u ? u : UUID.fromString(value.toString()));
  }

  @Override
  public void withdraw(long slotId) {
    entityManager
        .createNativeQuery("DELETE FROM queue_entry WHERE id = ? AND status = 'QUEUED'")
        .setParameter(1, slotId)
        .executeUpdate();
  }

  @Override
  public Optional<QueueSlot> top(long guildId) {
    return repository
        .findFirstByGuildIdAndStatusOrderByPositionAsc(guildId, QUEUED)
        .map(JpaQueueAdapter::toSlot);
  }

  @Override
  public void markPlayed(long slotId, int week) {
    entityManager
        .createNativeQuery(
            "UPDATE queue_entry SET status = 'PLAYED', position = NULL, played_week = ?"
                + " WHERE id = ?")
        .setParameter(1, week)
        .setParameter(2, slotId)
        .executeUpdate();
  }

  @Override
  public void shiftUp(long guildId) {
    // Step 1: negate the queued positions (all distinct, now all negative).
    entityManager
        .createNativeQuery(
            "UPDATE queue_entry SET position = -position"
                + " WHERE guild_id = ? AND status = 'QUEUED' AND position IS NOT NULL")
        .setParameter(1, guildId)
        .executeUpdate();
    // Step 2: renumber to a dense 1..n in the original order. Targets (positive) never collide with
    // the current (negative) values, so the partial unique index holds throughout the statement.
    entityManager
        .createNativeQuery(
            "UPDATE queue_entry q SET position = CAST(s.rn AS int) FROM ("
                + " SELECT id, ROW_NUMBER() OVER (ORDER BY position DESC) AS rn FROM queue_entry"
                + " WHERE guild_id = ? AND status = 'QUEUED' AND position IS NOT NULL) s"
                + " WHERE q.id = s.id")
        .setParameter(1, guildId)
        .executeUpdate();
  }

  @Override
  public void bumpSwap(long guildId, long slotId, int currentPosition) {
    // Three steps through a free temp position (0) so the partial unique index on
    // (guild_id, position) is never violated mid-statement.
    entityManager
        .createNativeQuery("UPDATE queue_entry SET position = 0 WHERE id = ? AND status = 'QUEUED'")
        .setParameter(1, slotId)
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "UPDATE queue_entry SET position = ?"
                + " WHERE guild_id = ? AND status = 'QUEUED' AND position = ?")
        .setParameter(1, currentPosition)
        .setParameter(2, guildId)
        .setParameter(3, currentPosition - 1)
        .executeUpdate();
    entityManager
        .createNativeQuery("UPDATE queue_entry SET position = ? WHERE id = ? AND status = 'QUEUED'")
        .setParameter(1, currentPosition - 1)
        .setParameter(2, slotId)
        .executeUpdate();
  }

  @Override
  public void addCoinsSpent(long slotId, int amount) {
    entityManager
        .createNativeQuery("UPDATE queue_entry SET coins_spent = coins_spent + ? WHERE id = ?")
        .setParameter(1, amount)
        .setParameter(2, slotId)
        .executeUpdate();
  }

  @Override
  public int otherQueuedCount(long guildId, long excludingSlotId) {
    Object count =
        entityManager
            .createNativeQuery(
                "SELECT count(*) FROM queue_entry"
                    + " WHERE guild_id = ? AND status = 'QUEUED' AND id <> ?")
            .setParameter(1, guildId)
            .setParameter(2, excludingSlotId)
            .getSingleResult();
    return ((Number) count).intValue();
  }

  private static QueueSlot toSlot(QueueEntryEntity e) {
    CapturedGame game =
        new CapturedGame(
            e.getApplicationId(),
            e.getGameName(),
            e.getRpDetails(),
            e.getRpState(),
            e.getRpLargeImage(),
            e.getRpSmallImage(),
            e.getSnapshot());
    // upvoteCount is overlaid by the view path (US3); queue write paths don't need it.
    return new QueueSlot(
        e.getId(),
        e.getGuildId(),
        e.getProposerMemberId(),
        game,
        new GameIdentity(e.getGameIdentity()),
        e.getGameInstanceId(),
        e.getPosition(),
        QueueStatus.valueOf(e.getStatus()),
        e.getCoinsSpent(),
        0,
        e.getPlayedWeek());
  }
}
