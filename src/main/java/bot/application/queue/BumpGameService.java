package bot.application.queue;

import bot.application.queue.BumpGameResult.Outcome;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.MovementRecord;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.domain.queue.QueueConfigPort;
import bot.domain.queue.QueueLedgerPolicy;
import bot.domain.queue.QueueOrderingPolicy;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bump write path (US4, FR-004/005/006/007). Under the per-guild queue lock (then the
 * per-account lock), it swaps the caller's own queued slot one position up — a single swap that
 * never removes or reorders any unrelated entry — and posts the bump spend through the shared
 * MEMBER↔POT ledger. Bumping a top slot, a non-existent slot, or one the member cannot afford
 * changes nothing. Acting only on the caller's own slot makes a "not owner" case impossible (FR-005
 * by construction).
 */
@Service
public class BumpGameService {

  private final QueuePort queuePort;
  private final QueueConfigPort configPort;
  private final CoinLedgerPort ledgerPort;

  public BumpGameService(
      QueuePort queuePort, QueueConfigPort configPort, CoinLedgerPort ledgerPort) {
    this.queuePort = queuePort;
    this.configPort = configPort;
    this.ledgerPort = ledgerPort;
  }

  @Transactional
  public BumpGameResult bump(BumpGameRequest request) {
    long guildId = request.guildId();
    long memberId = request.memberId();
    long interactionId = request.interactionId();

    queuePort.lockQueue(guildId);

    // Idempotency first (FR-015): a re-delivered bump must not move the slot again.
    Optional<MovementRecord> applied = ledgerPort.findByInteractionId(interactionId);
    if (applied.isPresent()) {
      int position = queuePort.ownQueued(guildId, memberId).map(QueueSlot::position).orElse(0);
      return new BumpGameResult(
          Outcome.DUPLICATE,
          position,
          applied.get().requested(),
          ledgerPort.currentBalance(guildId, memberId));
    }

    Optional<QueueSlot> own = queuePort.ownQueued(guildId, memberId);
    if (own.isEmpty()) {
      return new BumpGameResult(
          Outcome.NO_QUEUED, 0, 0, ledgerPort.currentBalance(guildId, memberId));
    }

    QueueSlot slot = own.get();
    int position = slot.position();
    if (position <= 1) {
      // Already at the top (FR-006): nothing charged, nothing reordered.
      return new BumpGameResult(
          Outcome.AT_TOP, position, 0, ledgerPort.currentBalance(guildId, memberId));
    }

    int bumpCost = configPort.get(guildId).bumpCost();
    ledgerPort.lockAccount(guildId, memberId);
    int balance = ledgerPort.currentBalance(guildId, memberId);
    PostingPlan plan =
        QueueLedgerPolicy.planSpend(memberId, balance, bumpCost, AdjustmentType.QUEUE_BUMP);

    int newPosition = QueueOrderingPolicy.bumpedPosition(position);
    queuePort.bumpSwap(guildId, slot.id(), position);
    queuePort.addCoinsSpent(slot.id(), bumpCost);

    NewMovement movement =
        new NewMovement(
            guildId,
            memberId,
            memberId,
            AdjustmentType.QUEUE_BUMP,
            plan.requested(),
            plan.credited(),
            plan.forfeited(),
            null,
            interactionId);
    ledgerPort.append(movement, plan);

    return new BumpGameResult(Outcome.BUMPED, newPosition, bumpCost, balance - bumpCost);
  }
}
