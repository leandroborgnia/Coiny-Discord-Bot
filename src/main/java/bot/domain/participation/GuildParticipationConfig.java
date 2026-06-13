package bot.domain.participation;

/**
 * Per-server participation settings: the earning {@link ParticipationRate} and the
 * free-first-proposal bootstrap toggle (FR-017/018). Absent storage ⇒ {@link #defaults(long)} (rate
 * 60/1, toggle off), so earning works given a designated channel and a current game without an
 * admin having to initialize anything first.
 *
 * <p>Pure domain type — no framework imports.
 */
public record GuildParticipationConfig(
    long guildId, ParticipationRate rate, boolean freeFirstProposal) {

  /** The effective config for a server with no stored row: rate 60/1, free-first-proposal off. */
  public static GuildParticipationConfig defaults(long guildId) {
    return new GuildParticipationConfig(guildId, ParticipationRate.defaults(), false);
  }
}
