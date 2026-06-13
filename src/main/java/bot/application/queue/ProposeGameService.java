package bot.application.queue;

import bot.application.queue.ProposeGameResult.Outcome;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.domain.participation.ParticipationConfigPort;
import bot.domain.queue.AnnouncementView;
import bot.domain.queue.CapturedGame;
import bot.domain.queue.CooldownPolicy;
import bot.domain.queue.CooldownPort;
import bot.domain.queue.GameIdentity;
import bot.domain.queue.GuildQueueConfig;
import bot.domain.queue.NewSlot;
import bot.domain.queue.NotEligibleException;
import bot.domain.queue.QueueConfigPort;
import bot.domain.queue.QueueLedgerPolicy;
import bot.domain.queue.QueueOrderingPolicy;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import bot.domain.queue.UpvotePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The propose write path (US1). Under the per-guild queue lock (then the per-account lock), it
 * guards no-activity, dedupes by interaction id, replaces an existing proposal for free, enforces
 * the cooldown and affordability, instant-pops the first game on an empty server, and otherwise
 * appends at the tail — posting the coin spend through the shared MEMBER↔POT ledger. Rule
 * violations throw typed {@code DomainException}s that roll the transaction back so nothing
 * changes.
 *
 * <p>Lock order is always queue → account (consistent across all queue services; no deadlock).
 */
@Service
public class ProposeGameService {

  private final QueuePort queuePort;
  private final QueueConfigPort configPort;
  private final CooldownPort cooldownPort;
  private final RotationStatePort rotationPort;
  private final UpvotePort upvotePort;
  private final CoinLedgerPort ledgerPort;
  private final AnnouncementAssembler announcementAssembler;
  private final ParticipationConfigPort participationConfigPort;

  public ProposeGameService(
      QueuePort queuePort,
      QueueConfigPort configPort,
      CooldownPort cooldownPort,
      RotationStatePort rotationPort,
      UpvotePort upvotePort,
      CoinLedgerPort ledgerPort,
      AnnouncementAssembler announcementAssembler,
      ParticipationConfigPort participationConfigPort) {
    this.queuePort = queuePort;
    this.configPort = configPort;
    this.cooldownPort = cooldownPort;
    this.rotationPort = rotationPort;
    this.upvotePort = upvotePort;
    this.ledgerPort = ledgerPort;
    this.announcementAssembler = announcementAssembler;
    this.participationConfigPort = participationConfigPort;
  }

  @Transactional
  public ProposeGameResult propose(ProposeGameRequest request) {
    CapturedGame game = request.game();
    if (game == null) {
      // No-activity guard (FR-035): no lock, no charge, nothing changed.
      return new ProposeGameResult(Outcome.NO_ACTIVITY, 0, false, 0, 0);
    }

    long guildId = request.guildId();
    long memberId = request.memberId();
    long interactionId = request.interactionId();

    queuePort.lockQueue(guildId);

    // Idempotency for new proposals (FR-015): a re-delivered propose returns the original slot.
    Optional<QueueSlot> already = queuePort.findByProposeInteraction(interactionId);
    if (already.isPresent()) {
      QueueSlot slot = already.get();
      return new ProposeGameResult(
          Outcome.DUPLICATE,
          positionOf(slot),
          false,
          slot.coinsSpent(),
          ledgerPort.currentBalance(guildId, memberId));
    }

    // Replace branch (FR-034): editing the existing single entry is free and resets its upvotes.
    Optional<QueueSlot> own = queuePort.ownQueued(guildId, memberId);
    if (own.isPresent()) {
      QueueSlot slot = own.get();
      UUID newInstance = UUID.randomUUID();
      queuePort.replaceGame(slot.id(), game, GameIdentity.of(game), newInstance);
      upvotePort.resetForSlot(slot.id());
      return new ProposeGameResult(
          Outcome.REPLACED,
          positionOf(slot),
          false,
          0,
          ledgerPort.currentBalance(guildId, memberId));
    }

    // Eligibility (FR-011): the member must hold no queued game (proven above) and be off cooldown.
    int gamesRemaining = cooldownPort.gamesRemaining(guildId, memberId);
    if (!CooldownPolicy.eligible(false, gamesRemaining)) {
      throw new NotEligibleException(gamesRemaining);
    }

    GuildQueueConfig config = configPort.get(guildId);
    int proposeCost = config.proposeCost();
    GameIdentity identity = GameIdentity.of(game);
    Instant now = Instant.now();

    // Read the rotation/queue state before taking the account lock (the bootstrap test and the
    // free-first-proposal waiver both decide off it; lock order stays queue → account).
    RotationState rotation = rotationPort.get(guildId);
    List<QueueSlot> queued = queuePort.queued(guildId);
    boolean bootstrap = rotation.currentSlotId() == null && queued.isEmpty();

    // Free-first-proposal waiver (FR-018): in the no-current-game + empty-queue bootstrap state
    // with
    // the toggle on, accept the proposal at no cost — no account lock, no spend, no coin movement,
    // balance unchanged. Scoped exactly to that recurring cold-start state.
    if (bootstrap && participationConfigPort.freeFirstProposalEnabled(guildId)) {
      UUID instanceId = UUID.randomUUID();
      QueueSlot slot =
          queuePort.append(
              NewSlot.instantPopped(
                  guildId, memberId, game, identity, instanceId, 0, 0, interactionId));
      rotationPort.recordDesignation(guildId, 0, slot.id(), identity, now);
      rotationPort.bootstrap(guildId, slot.id(), now);
      cooldownPort.set(guildId, memberId, 0);
      int balance = ledgerPort.currentBalance(guildId, memberId);
      Optional<AnnouncementView> announcement =
          config.hasAnnouncementChannel()
              ? announcementAssembler.assemble(guildId)
              : Optional.empty();
      return new ProposeGameResult(Outcome.INSTANT_POPPED, 0, true, 0, balance, announcement);
    }

    // Affordability (FR-002): builds the balanced spend; throws InsufficientCoinsException if
    // short.
    ledgerPort.lockAccount(guildId, memberId);
    int balance = ledgerPort.currentBalance(guildId, memberId);
    PostingPlan plan =
        QueueLedgerPolicy.planSpend(memberId, balance, proposeCost, AdjustmentType.QUEUE_PROPOSE);

    // Bootstrap instant-pop (FR-024): with no current game and an empty queue, the first proposal
    // becomes this week's game immediately; counts as the proposer's game played with N = 0.
    if (bootstrap) {
      UUID instanceId = UUID.randomUUID();
      QueueSlot slot =
          queuePort.append(
              NewSlot.instantPopped(
                  guildId, memberId, game, identity, instanceId, proposeCost, 0, interactionId));
      rotationPort.recordDesignation(guildId, 0, slot.id(), identity, now);
      rotationPort.bootstrap(guildId, slot.id(), now);
      cooldownPort.set(guildId, memberId, 0);
      postSpend(guildId, memberId, plan, interactionId);
      // Announce the freshly designated game (FR-024/FR-036), if a channel is configured. Assembled
      // in-transaction (current slot is now this game); the handler posts it after commit.
      Optional<AnnouncementView> announcement =
          config.hasAnnouncementChannel()
              ? announcementAssembler.assemble(guildId)
              : Optional.empty();
      return new ProposeGameResult(
          Outcome.INSTANT_POPPED, 0, true, proposeCost, balance - proposeCost, announcement);
    }

    // Normal proposal: append at the tail.
    int position = QueueOrderingPolicy.appendPosition(queued.size());
    UUID instanceId = UUID.randomUUID();
    QueueSlot slot =
        queuePort.append(
            NewSlot.queued(
                guildId,
                memberId,
                game,
                identity,
                instanceId,
                position,
                proposeCost,
                interactionId));
    postSpend(guildId, memberId, plan, interactionId);
    return new ProposeGameResult(
        Outcome.PROPOSED, positionOf(slot, position), false, proposeCost, balance - proposeCost);
  }

  private void postSpend(long guildId, long memberId, PostingPlan plan, long interactionId) {
    // moderator_id carries the acting member's own id (self-initiated spend).
    NewMovement movement =
        new NewMovement(
            guildId,
            memberId,
            memberId,
            AdjustmentType.QUEUE_PROPOSE,
            plan.requested(),
            plan.credited(),
            plan.forfeited(),
            null,
            interactionId);
    ledgerPort.append(movement, plan);
  }

  private static int positionOf(QueueSlot slot) {
    return slot.position() == null ? 0 : slot.position();
  }

  private static int positionOf(QueueSlot slot, int fallback) {
    return slot.position() == null ? fallback : slot.position();
  }
}
