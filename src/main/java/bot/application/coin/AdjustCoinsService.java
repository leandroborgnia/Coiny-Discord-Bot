package bot.application.coin;

import bot.application.coin.AdjustCoinsResult.Outcome;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.AppendResult;
import bot.domain.coin.CoinAmount;
import bot.domain.coin.CoinLedgerPolicy;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.ModeratorNotAuthorizedException;
import bot.domain.coin.ModeratorRoleNotConfiguredException;
import bot.domain.coin.MovementRecord;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The coin write path. Opens the single transaction, authorizes against the server's configured
 * moderator role, enforces at-most-once via the interaction id, then under a per-account advisory
 * lock derives the balance, applies the {@link CoinLedgerPolicy}, and appends a balanced movement.
 * Rule violations throw typed {@code DomainException}s that roll the transaction back.
 */
@Service
public class AdjustCoinsService {

  private final CoinLedgerPort ledgerPort;
  private final GuildCoinConfigPort configPort;

  public AdjustCoinsService(CoinLedgerPort ledgerPort, GuildCoinConfigPort configPort) {
    this.ledgerPort = ledgerPort;
    this.configPort = configPort;
  }

  @Transactional
  public AdjustCoinsResult adjust(AdjustCoinsRequest request) {
    CoinAmount.positive(request.amount()); // reject 0/negative amounts (FR-016)

    GuildCoinConfig config = configPort.get(request.guildId());
    authorize(request, config);

    // Idempotency: a previously applied invocation returns its original outcome (no write).
    Optional<MovementRecord> existing = ledgerPort.findByInteractionId(request.interactionId());
    if (existing.isPresent()) {
      MovementRecord m = existing.get();
      int balance = ledgerPort.currentBalance(request.guildId(), request.targetMemberId());
      return new AdjustCoinsResult(
          Outcome.DUPLICATE, balance, m.credited(), m.forfeited(), config.cap());
    }

    ledgerPort.lockAccount(request.guildId(), request.targetMemberId());
    int balance = ledgerPort.currentBalance(request.guildId(), request.targetMemberId());

    PostingPlan plan = planFor(request, balance, config.cap());
    NewMovement movement =
        new NewMovement(
            request.guildId(),
            request.targetMemberId(),
            request.actorMemberId(),
            request.type(),
            plan.requested(),
            plan.credited(),
            plan.forfeited(),
            request.reason(),
            request.interactionId());

    AppendResult appended = ledgerPort.append(movement, plan);
    if (!appended.inserted()) {
      // Lost an idempotency race after the lock; treat as duplicate, nothing new applied.
      MovementRecord m = appended.movement();
      return new AdjustCoinsResult(
          Outcome.DUPLICATE, balance, m.credited(), m.forfeited(), config.cap());
    }

    int delta = request.type() == AdjustmentType.GRANT ? plan.credited() : -plan.requested();
    return new AdjustCoinsResult(
        Outcome.APPLIED, balance + delta, plan.credited(), plan.forfeited(), config.cap());
  }

  private static void authorize(AdjustCoinsRequest request, GuildCoinConfig config) {
    if (!config.hasModeratorRole()) {
      throw new ModeratorRoleNotConfiguredException();
    }
    boolean hasRole = request.actorRoleIds().contains(config.moderatorRoleId());
    if (!request.actorIsAdmin() && !hasRole) {
      throw ModeratorNotAuthorizedException.missingRole();
    }
  }

  private static PostingPlan planFor(AdjustCoinsRequest request, int balance, int cap) {
    return request.type() == AdjustmentType.GRANT
        ? CoinLedgerPolicy.planGrant(
            request.guildId(), request.targetMemberId(), balance, request.amount(), cap)
        : CoinLedgerPolicy.planDeduction(
            request.guildId(), request.targetMemberId(), balance, request.amount());
  }
}
