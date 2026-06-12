package bot.application.queue;

import bot.application.queue.WithdrawGameResult.Outcome;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.MovementRecord;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.domain.queue.QueueLedgerPolicy;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The withdraw write path (US1, FR-033). Under the per-guild queue lock (then the per-account
 * lock), it removes the member's own still-queued slot, closes the position gap, and refunds
 * exactly the coins spent on that slot as a new reversing MEMBER↔POT movement (the pot is debited).
 * A played slot cannot be withdrawn (it is no longer the member's queued slot).
 */
@Service
public class WithdrawGameService {

  private final QueuePort queuePort;
  private final CoinLedgerPort ledgerPort;

  public WithdrawGameService(QueuePort queuePort, CoinLedgerPort ledgerPort) {
    this.queuePort = queuePort;
    this.ledgerPort = ledgerPort;
  }

  @Transactional
  public WithdrawGameResult withdraw(WithdrawGameRequest request) {
    long guildId = request.guildId();
    long memberId = request.memberId();
    long interactionId = request.interactionId();

    queuePort.lockQueue(guildId);

    // Idempotency first (FR-015): a re-delivered withdraw must report DUPLICATE, not NO_QUEUED
    // (the slot is already gone). Keyed by the refund movement's interaction id.
    Optional<MovementRecord> applied = ledgerPort.findByInteractionId(interactionId);
    if (applied.isPresent()) {
      return new WithdrawGameResult(
          Outcome.DUPLICATE,
          applied.get().requested(),
          ledgerPort.currentBalance(guildId, memberId));
    }

    Optional<QueueSlot> own = queuePort.ownQueued(guildId, memberId);
    if (own.isEmpty()) {
      return new WithdrawGameResult(
          Outcome.NO_QUEUED, 0, ledgerPort.currentBalance(guildId, memberId));
    }

    QueueSlot slot = own.get();
    int refund = slot.coinsSpent();
    ledgerPort.lockAccount(guildId, memberId);
    int balance = ledgerPort.currentBalance(guildId, memberId);

    queuePort.withdraw(slot.id());
    queuePort.shiftUp(guildId);

    PostingPlan plan = QueueLedgerPolicy.planRefund(memberId, refund);
    NewMovement movement =
        new NewMovement(
            guildId,
            memberId,
            memberId,
            AdjustmentType.QUEUE_REFUND,
            plan.requested(),
            plan.credited(),
            plan.forfeited(),
            null,
            interactionId);
    ledgerPort.append(movement, plan);

    return new WithdrawGameResult(Outcome.WITHDRAWN, refund, balance + refund);
  }
}
