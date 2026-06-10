package bot.infrastructure.persistence.coin;

import bot.domain.coin.AdjustmentType;
import bot.domain.coin.AppendResult;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.MovementRecord;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingLine;
import bot.domain.coin.PostingPlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * JPA/Postgres adapter for the append-only coin ledger. Called inside the application's
 * transaction: the advisory lock is transaction-scoped, the balance is derived by summing entries,
 * and the movement insert is idempotent via {@code ON CONFLICT (interaction_id) DO NOTHING}.
 */
@Component
public class JpaCoinLedgerAdapter implements CoinLedgerPort {

  private static final long LOCK_MIX = 0x9E3779B97F4A7C15L; // Fibonacci hashing constant

  @PersistenceContext private EntityManager entityManager;

  private final CoinMovementJpaRepository movementRepository;
  private final CoinLedgerEntryJpaRepository entryRepository;

  public JpaCoinLedgerAdapter(
      CoinMovementJpaRepository movementRepository, CoinLedgerEntryJpaRepository entryRepository) {
    this.movementRepository = movementRepository;
    this.entryRepository = entryRepository;
  }

  @Override
  public void lockAccount(long guildId, long memberId) {
    long key = guildId * LOCK_MIX + memberId;
    entityManager
        .createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
        .setParameter("key", key)
        .getSingleResult();
  }

  @Override
  public int currentBalance(long guildId, long memberId) {
    return (int) entryRepository.sumMemberBalance(guildId, memberId);
  }

  @Override
  public Optional<MovementRecord> findByInteractionId(long interactionId) {
    return movementRepository
        .findByInteractionId(interactionId)
        .map(JpaCoinLedgerAdapter::toRecord);
  }

  @Override
  public AppendResult append(NewMovement movement, PostingPlan plan) {
    Query insert =
        entityManager
            .createNativeQuery(
                "INSERT INTO coin_movement"
                    + " (guild_id, member_id, moderator_id, type, requested_amount,"
                    + " credited_amount, forfeited_amount, reason, interaction_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT (interaction_id) DO NOTHING"
                    + " RETURNING id")
            .setParameter(1, movement.guildId())
            .setParameter(2, movement.memberId())
            .setParameter(3, movement.moderatorId())
            .setParameter(4, movement.type().name())
            .setParameter(5, movement.requested())
            .setParameter(6, movement.credited())
            .setParameter(7, movement.forfeited())
            .setParameter(8, movement.reason())
            .setParameter(9, movement.interactionId());

    List<?> ids = insert.getResultList();
    if (ids.isEmpty()) {
      // A concurrent duplicate won the race; return the originally recorded movement.
      MovementRecord original =
          movementRepository
              .findByInteractionId(movement.interactionId())
              .map(JpaCoinLedgerAdapter::toRecord)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "idempotent insert no-op but no existing movement"));
      return new AppendResult(original, false);
    }

    long movementId = ((Number) ids.get(0)).longValue();
    for (PostingLine line : plan.lines()) {
      entryRepository.save(
          new CoinLedgerEntryEntity(
              movementId,
              movement.guildId(),
              line.account().name(),
              line.memberId(),
              line.signedAmount()));
    }
    MovementRecord record =
        movementRepository
            .findById(movementId)
            .map(JpaCoinLedgerAdapter::toRecord)
            .orElseThrow(() -> new IllegalStateException("movement vanished after insert"));
    return new AppendResult(record, true);
  }

  @Override
  public List<MovementRecord> recentHistory(long guildId, long memberId, int limit) {
    return movementRepository
        .findByGuildIdAndMemberIdOrderByIdDesc(guildId, memberId, Limit.of(limit))
        .stream()
        .map(JpaCoinLedgerAdapter::toRecord)
        .toList();
  }

  private static MovementRecord toRecord(CoinMovementEntity e) {
    return new MovementRecord(
        e.getId(),
        e.getGuildId(),
        e.getMemberId(),
        e.getModeratorId(),
        AdjustmentType.valueOf(e.getType()),
        e.getRequestedAmount(),
        e.getCreditedAmount(),
        e.getForfeitedAmount(),
        e.getReason(),
        e.getInteractionId(),
        e.getCreatedAt().toInstant());
  }
}
