package bot.domain.coin;

/**
 * The account kinds whose entries balance to zero within a movement. {@code TREASURY} is the
 * per-guild mint/source for grants and sink for deductions; {@code FORFEIT} is the per-guild sink
 * for over-cap coins. {@code POT} is the per-guild account that receives coins spent on the game
 * queue (propose/bump) as the balanced counter-entry, and reverses them on a withdraw refund
 * (feature 004). Only {@code MEMBER} entries carry a member id and contribute to a member's derived
 * balance; POT (like TREASURY/FORFEIT) is not non-negativity-checked.
 */
public enum LedgerAccount {
  MEMBER,
  TREASURY,
  FORFEIT,
  POT
}
