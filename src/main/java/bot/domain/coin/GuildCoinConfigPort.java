package bot.domain.coin;

/** Outbound port for per-server coin configuration (moderator role + balance cap). */
public interface GuildCoinConfigPort {

  /** The server's config, or a default (cap 12, no moderator role) when none is stored yet. */
  GuildCoinConfig get(long guildId);

  /**
   * Upsert the server's config. A {@code null} {@code moderatorRoleId} or {@code cap} leaves that
   * setting unchanged. Returns the effective config after the change.
   */
  GuildCoinConfig upsert(long guildId, Long moderatorRoleId, Integer cap);
}
