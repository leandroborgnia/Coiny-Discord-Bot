package bot.domain.participation;

import java.time.Instant;

/**
 * Outbound port for per-member accrual state and the synthetic drop-id sequence. The application
 * calls these inside a single transaction under the per-account advisory lock; the transactional
 * {@code upsert} of the consumed seconds is the at-most-once guard (a re-run cannot re-credit a
 * consumed span).
 */
public interface ParticipationAccrualPort {

  /** The member's accrual state, or {@code (0, null)} when absent. */
  ParticipationAccrual get(long guildId, long memberId);

  /** Upsert the member's banked seconds and last-sampled instant. */
  void upsert(long guildId, long memberId, long bankedSeconds, Instant lastSampledAt);

  /** A fresh, collision-free synthetic ledger id ({@code -nextval('participation_drop_seq')}). */
  long nextDropId();
}
