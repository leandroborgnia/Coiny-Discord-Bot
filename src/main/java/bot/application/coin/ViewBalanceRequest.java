package bot.application.coin;

/** Request for a member's own balance and recent history (bounded by {@code historyLimit}). */
public record ViewBalanceRequest(long guildId, long memberId, int historyLimit) {}
