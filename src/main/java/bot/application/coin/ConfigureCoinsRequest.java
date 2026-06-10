package bot.application.coin;

/**
 * Request to configure a server's coin settings. {@code moderatorRoleId}/{@code cap} are nullable —
 * a null leaves that setting unchanged.
 */
public record ConfigureCoinsRequest(
    long guildId, boolean actorIsAdmin, Long moderatorRoleId, Integer cap) {}
