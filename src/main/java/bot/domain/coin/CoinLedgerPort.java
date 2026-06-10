package bot.domain.coin;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for the append-only coin ledger. The application calls these inside a single
 * transaction; the implementation owns the database specifics (advisory lock, derived-balance sum,
 * {@code ON CONFLICT} append). Balances are always derived, never stored.
 */
public interface CoinLedgerPort {

  /** Serialize concurrent adjustments to one account (transaction-level advisory lock). */
  void lockAccount(long guildId, long memberId);

  /** The member's current balance, derived by summing their MEMBER entries (0 if none). */
  int currentBalance(long guildId, long memberId);

  /** Look up a movement by its idempotency (interaction) id, if already applied. */
  Optional<MovementRecord> findByInteractionId(long interactionId);

  /** Append a movement and its balanced postings; idempotent on the interaction id. */
  AppendResult append(NewMovement movement, PostingPlan plan);

  /** The member's most recent movements, newest first, bounded by {@code limit}. */
  List<MovementRecord> recentHistory(long guildId, long memberId, int limit);
}
