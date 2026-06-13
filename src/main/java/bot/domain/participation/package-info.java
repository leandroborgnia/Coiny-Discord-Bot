/**
 * Participation-earning domain core — pure Java, no Spring/JDA/JPA imports. Holds the per-server
 * rate and config value objects, the per-member accrual state, the time-arithmetic accrual policy,
 * and the outbound ports the application layer drives. The coin/cap math is NOT duplicated here: a
 * minted drop reuses the coin feature's {@code CoinLedgerPolicy.planGrant} and one append-only
 * ledger (no second economy). The application layer depends inward on these types.
 */
package bot.domain.participation;
