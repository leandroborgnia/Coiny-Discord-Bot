package bot.domain.coin;

import java.time.Instant;

/** A persisted movement read back from the ledger (for history and idempotency results). */
public record MovementRecord(
    long id,
    long guildId,
    long memberId,
    long moderatorId,
    AdjustmentType type,
    int requested,
    int credited,
    int forfeited,
    String reason,
    long interactionId,
    Instant createdAt) {}
