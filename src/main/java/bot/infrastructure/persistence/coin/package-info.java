/**
 * Coin economy persistence adapters — JPA entities, Spring Data repositories, and the adapters that
 * implement the domain coin ports against Postgres (advisory locks, derived-balance sums, and
 * {@code ON CONFLICT}-guarded appends).
 */
package bot.infrastructure.persistence.coin;
