package bot.domain.skipjar;

/**
 * Outbound port for the per-run skip-contribution log (the jar count and once-per-run guard). The
 * application calls these inside the contribution transaction; the implementation owns the Postgres
 * specifics (the composite PK {@code (guild_id, week_number, member_id)} enforcing once-per-run, a
 * count over its {@code (guild_id, week_number)} prefix).
 */
public interface SkipContributionPort {

  /** Whether this member has already contributed for the given run. */
  boolean hasContributed(long guildId, int weekNumber, long memberId);

  /** The jar count for the given run (distinct contributing members). */
  int count(long guildId, int weekNumber);

  /**
   * Record this member's contribution for the run, referencing the backing SKIP_JAR movement. The
   * PK makes a second record for the same run a unique violation (once-per-run, FR-002).
   */
  void record(long guildId, int weekNumber, long memberId, long movementId);
}
