/**
 * JPA/Postgres adapters implementing the {@code bot.domain.participation} outbound ports:
 * per-server participation config, the designated-voice-channel set, per-member accrual state (with
 * the negative-namespaced drop-id sequence), and the current-game read joined off the queue tables.
 * Participation credits themselves reuse the coin ledger adapter — there is no participation
 * ledger.
 */
package bot.infrastructure.persistence.participation;
