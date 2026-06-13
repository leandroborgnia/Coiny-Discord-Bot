package bot.application.skipjar;

import bot.application.queue.AdvanceResult;
import bot.application.queue.AdvanceRotationService;
import bot.domain.coin.AppendResult;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.MovementRecord;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import bot.domain.skipjar.AlreadyContributedException;
import bot.domain.skipjar.EarnerStatsPort;
import bot.domain.skipjar.GuildSkipJarConfig;
import bot.domain.skipjar.JarClosedException;
import bot.domain.skipjar.NoCurrentGameException;
import bot.domain.skipjar.NotEligibleToContributeException;
import bot.domain.skipjar.SkipContributionPort;
import bot.domain.skipjar.SkipJarConfigPort;
import bot.domain.skipjar.SkipJarLedgerPolicy;
import bot.domain.skipjar.SkipThresholdPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The skip-jar vote write path (US1 + US2). Under the per-guild queue advisory lock (then the
 * per-account lock — same lock order as {@code ProposeGameService}, no deadlock) it: dedupes by
 * interaction id, reads the current run, gates on dwell/earner/once-per-run, posts the one
 * non-refundable coin through the shared MEMBER ↔ SKIP_POT ledger, records the contribution, and —
 * when the jar reaches the threshold — triggers exactly one early rotation advance in the same
 * transaction (no double-advance, FR-011). Rule violations throw typed {@code DomainException}s
 * that roll the transaction back so nothing changes (no charge, no row).
 */
@Service
public class ContributeToSkipJarService {

  private final QueuePort queuePort;
  private final RotationStatePort rotationStatePort;
  private final SkipJarConfigPort skipJarConfigPort;
  private final EarnerStatsPort earnerStatsPort;
  private final SkipContributionPort skipContributionPort;
  private final CoinLedgerPort coinLedgerPort;
  private final AdvanceRotationService advanceRotationService;

  public ContributeToSkipJarService(
      QueuePort queuePort,
      RotationStatePort rotationStatePort,
      SkipJarConfigPort skipJarConfigPort,
      EarnerStatsPort earnerStatsPort,
      SkipContributionPort skipContributionPort,
      CoinLedgerPort coinLedgerPort,
      AdvanceRotationService advanceRotationService) {
    this.queuePort = queuePort;
    this.rotationStatePort = rotationStatePort;
    this.skipJarConfigPort = skipJarConfigPort;
    this.earnerStatsPort = earnerStatsPort;
    this.skipContributionPort = skipContributionPort;
    this.coinLedgerPort = coinLedgerPort;
    this.advanceRotationService = advanceRotationService;
  }

  @Transactional
  public ContributeResult contribute(ContributeRequest request) {
    long guildId = request.guildId();
    long memberId = request.memberId();
    long interactionId = request.interactionId();
    Instant now = request.now();

    // 1. Serialize the whole vote + rotation under the per-guild queue advisory lock (Principle
    // IV).
    queuePort.lockQueue(guildId);

    // 2. Idempotent replay: a re-delivered interaction returns the prior outcome with no new
    // charge.
    Optional<MovementRecord> existing = coinLedgerPort.findByInteractionId(interactionId);
    if (existing.isPresent()) {
      return replay(guildId);
    }

    // 3. Current run (none ⇒ nothing to skip).
    RotationState state = rotationStatePort.get(guildId);
    if (state.currentSlot().isEmpty()) {
      throw new NoCurrentGameException();
    }
    Instant becameCurrent = state.lastPopAt(); // dwell baseline + earner-run boundary
    int week = state.currentWeekNumber(); // the run key scoping contributions
    String gameName = gameName(state);

    // 4. Dwell gate (FR-007).
    GuildSkipJarConfig cfg = skipJarConfigPort.get(guildId);
    if (Duration.between(becameCurrent, now).compareTo(cfg.dwell()) < 0) {
      throw new JarClosedException(gameName, becameCurrent.plus(cfg.dwell()));
    }

    // 5. Participation gate (FR-004; gate off ⇒ any member, FR-005).
    if (cfg.gateOn() && !earnerStatsPort.isEarner(guildId, memberId, becameCurrent)) {
      throw new NotEligibleToContributeException(gameName);
    }

    // 6. Once per run — optimistic check (the PK insert in step 8 is the backstop, FR-002).
    if (skipContributionPort.hasContributed(guildId, week, memberId)) {
      throw new AlreadyContributedException();
    }

    // 7. Debit one coin: lock the account AFTER the queue lock, then post MEMBER −1 / SKIP_POT +1.
    coinLedgerPort.lockAccount(guildId, memberId);
    int balance = coinLedgerPort.currentBalance(guildId, memberId);
    PostingPlan plan =
        SkipJarLedgerPolicy.planContribution(memberId, balance); // OverdrawException if <1
    AppendResult applied =
        coinLedgerPort.append(
            new NewMovement(
                guildId,
                memberId,
                /* moderator */ memberId,
                plan.type(),
                plan.requested(),
                plan.credited(),
                plan.forfeited(),
                /* reason */ null,
                interactionId),
            plan);

    // 8. Record the vote (PK ⇒ once-per-run; a duplicate raises a unique violation → rollback).
    skipContributionPort.record(guildId, week, memberId, applied.movement().id());

    // 9. Evaluate the jar against the threshold at this moment.
    int count = skipContributionPort.count(guildId, week);
    int earners = earnerStatsPort.distinctEarnerCount(guildId, becameCurrent);
    int threshold = SkipThresholdPolicy.threshold(earners, cfg.thresholdFloor());

    // 10. Trigger exactly one early advance when the jar is full (FR-010), in this same locked txn.
    if (count >= threshold) {
      AdvanceResult advance = advanceRotationService.skip(guildId, now);
      RotationState newState = rotationStatePort.get(guildId); // the skip advanced the run
      String newGameName =
          newState
              .currentSlot()
              .flatMap(queuePort::findSlot)
              .map(s -> s.game().name())
              .orElse(null); // null when the new run is empty (queue exhausted)
      return new ContributeResult(
          true, count, threshold, 0, true, gameName, newGameName, advance.finalAnnouncement());
    }

    return new ContributeResult(
        true, count, threshold, threshold - count, false, gameName, null, Optional.empty());
  }

  /** Idempotent-replay outcome: recompute the current jar view, charged=false, no re-trigger. */
  private ContributeResult replay(long guildId) {
    RotationState state = rotationStatePort.get(guildId);
    if (state.currentSlot().isEmpty()) {
      return new ContributeResult(false, 0, 0, 0, false, null, null, Optional.empty());
    }
    GuildSkipJarConfig cfg = skipJarConfigPort.get(guildId);
    int count = skipContributionPort.count(guildId, state.currentWeekNumber());
    int earners = earnerStatsPort.distinctEarnerCount(guildId, state.lastPopAt());
    int threshold = SkipThresholdPolicy.threshold(earners, cfg.thresholdFloor());
    return new ContributeResult(
        false,
        count,
        threshold,
        Math.max(0, threshold - count),
        false,
        gameName(state),
        null,
        Optional.empty());
  }

  /**
   * The current game's display name from the queue slot (the same source AnnouncementAssembler
   * uses).
   */
  private String gameName(RotationState state) {
    return state
        .currentSlot()
        .flatMap(queuePort::findSlot)
        .map(QueueSlot::game)
        .map(g -> g.name())
        .orElse(null);
  }
}
