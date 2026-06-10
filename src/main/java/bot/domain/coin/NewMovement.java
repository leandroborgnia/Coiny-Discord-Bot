package bot.domain.coin;

/**
 * A movement to be appended (header fields); the balanced postings travel in a {@link PostingPlan}.
 */
public record NewMovement(
    long guildId,
    long memberId,
    long moderatorId,
    AdjustmentType type,
    int requested,
    int credited,
    int forfeited,
    String reason,
    long interactionId) {}
