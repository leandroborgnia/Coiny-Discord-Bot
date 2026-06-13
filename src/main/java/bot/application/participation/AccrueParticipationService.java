package bot.application.participation;

import bot.application.participation.AccrueParticipationResult.Outcome;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPolicy;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.domain.participation.GuildParticipationConfig;
import bot.domain.participation.ParticipationAccrual;
import bot.domain.participation.ParticipationAccrualPolicy;
import bot.domain.participation.ParticipationAccrualPort;
import bot.domain.participation.ParticipationConfigPort;
import bot.domain.participation.ParticipationRate;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The earning core (US1): accrues one qualifying member's elapsed time for a single sweep tick and
 * mints whole drops at the configured flat rate. The current-game gating is done by the sweep
 * before this call, so this service only measures time and credits coins.
 *
 * <p>Runs in one transaction under the reused per-account advisory lock (serializing with coin
 * spends/grants). The at-most-once guard is the transactional banked-seconds decrement + {@code
 * last_sampled_at} advance: a replayed tick cannot re-credit a consumed span. Each drop reuses the
 * existing {@link CoinLedgerPolicy#planGrant} cap/forfeiture math and the one append-only ledger —
 * no second economy, no new arithmetic.
 */
@Service
public class AccrueParticipationService {

  private final ParticipationConfigPort configPort;
  private final ParticipationAccrualPort accrualPort;
  private final CoinLedgerPort ledgerPort;
  private final GuildCoinConfigPort coinConfigPort;
  private final Duration maxGap;

  public AccrueParticipationService(
      ParticipationConfigPort configPort,
      ParticipationAccrualPort accrualPort,
      CoinLedgerPort ledgerPort,
      GuildCoinConfigPort coinConfigPort,
      @Value("${participation.sweep.max-gap}") String maxGap) {
    this.configPort = configPort;
    this.accrualPort = accrualPort;
    this.ledgerPort = ledgerPort;
    this.coinConfigPort = coinConfigPort;
    this.maxGap = Duration.parse(maxGap);
  }

  @Transactional
  public AccrueParticipationResult accrue(AccrueParticipationRequest request) {
    long guildId = request.guildId();
    long memberId = request.memberId();
    Instant now = request.now();

    ledgerPort.lockAccount(guildId, memberId);
    int cap = coinConfigPort.get(guildId).cap();
    int balance = ledgerPort.currentBalance(guildId, memberId);
    ParticipationAccrual acc = accrualPort.get(guildId, memberId);

    // Cap pause (I-P3/FR-005): no banking while already at the cap; only advance the sample clock.
    if (balance >= cap) {
      accrualPort.upsert(guildId, memberId, acc.bankedSeconds(), now);
      return new AccrueParticipationResult(0, 0, 0, acc.bankedSeconds(), Outcome.PAUSED_AT_CAP);
    }

    // Clamp elapsed (I-P4/FR-023): downtime / re-entry accrue 0 and only reset the sample clock.
    long elapsed = ParticipationAccrualPolicy.elapsedToCredit(acc.lastSampledAt(), now, maxGap);
    if (elapsed == 0) {
      accrualPort.upsert(guildId, memberId, acc.bankedSeconds(), now);
      return new AccrueParticipationResult(0, 0, 0, acc.bankedSeconds(), Outcome.FRESH_SESSION);
    }

    long newBanked = acc.bankedSeconds() + elapsed;
    GuildParticipationConfig config = configPort.get(guildId);
    ParticipationRate rate = config.rate();
    long threshold = ParticipationAccrualPolicy.thresholdSeconds(rate);

    int dropsMinted = 0;
    int coinsCredited = 0;
    int coinsForfeited = 0;
    while (newBanked >= threshold && balance < cap) {
      PostingPlan plan =
          CoinLedgerPolicy.planGrant(guildId, memberId, balance, rate.coinsPerDrop(), cap);
      long id = accrualPort.nextDropId();
      NewMovement movement =
          new NewMovement(
              guildId,
              memberId,
              memberId, // self-initiated, mirroring queue spends
              AdjustmentType.PARTICIPATION,
              plan.requested(),
              plan.credited(),
              plan.forfeited(),
              null,
              id);
      ledgerPort.append(movement, plan);

      balance += plan.credited();
      newBanked -= threshold;
      dropsMinted++;
      coinsCredited += plan.credited();
      coinsForfeited += plan.forfeited();

      // This drop hit the cap: stop minting whole drops; the member now pauses on the next tick.
      if (plan.forfeited() > 0) {
        break;
      }
    }

    accrualPort.upsert(guildId, memberId, newBanked, now);
    Outcome outcome = dropsMinted > 0 ? Outcome.MINTED : Outcome.ACCRUED;
    return new AccrueParticipationResult(
        dropsMinted, coinsCredited, coinsForfeited, newBanked, outcome);
  }
}
