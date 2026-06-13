/**
 * Participation application services — the only layer that opens transactions and drives the {@code
 * bot.domain.participation} ports. {@code AccrueParticipationService} mints earned drops (reusing
 * the coin ledger); {@code ConfigureParticipationService} applies the moderator-gated
 * channel/rate/toggle changes. Request record in, result record out.
 */
package bot.application.participation;
