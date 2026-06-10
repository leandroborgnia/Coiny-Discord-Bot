package bot.domain.coin;

/**
 * Per-server coin configuration. {@code moderatorRoleId} is {@code null} until a server designates
 * a role (the feature fails closed while null); {@code cap} is the maximum balance, defaulting to
 * 12.
 */
public record GuildCoinConfig(long guildId, Long moderatorRoleId, int cap) {

  public boolean hasModeratorRole() {
    return moderatorRoleId != null;
  }
}
