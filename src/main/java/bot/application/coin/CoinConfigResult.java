package bot.application.coin;

/** The effective per-server coin configuration after an upsert. */
public record CoinConfigResult(Long moderatorRoleId, int cap) {}
