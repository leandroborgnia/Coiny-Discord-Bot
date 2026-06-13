package bot.domain.skipjar;

import java.time.Instant;

/**
 * Outbound port for the distinct-earner set of the current run, read from the existing
 * participation ledger (no new column). An earner is a member with a {@code PARTICIPATION} movement
 * crediting ≥ 1 coin since the run boundary ({@code since = RotationState.lastPopAt()}).
 */
public interface EarnerStatsPort {

  /** Distinct members credited ≥ 1 PARTICIPATION coin for the current run (created_at ≥ since). */
  int distinctEarnerCount(long guildId, Instant since);

  /** Whether this member is such an earner (gate-on eligibility). */
  boolean isEarner(long guildId, long memberId, Instant since);
}
