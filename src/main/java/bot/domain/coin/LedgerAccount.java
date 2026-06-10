package bot.domain.coin;

/**
 * The account kinds whose entries balance to zero within a movement. {@code TREASURY} is the
 * per-guild mint/source for grants and sink for deductions; {@code FORFEIT} is the per-guild sink
 * for over-cap coins. Only {@code MEMBER} entries carry a member id and contribute to a member's
 * derived balance.
 */
public enum LedgerAccount {
  MEMBER,
  TREASURY,
  FORFEIT
}
