package bot.domain.queue;

import java.util.UUID;

/**
 * A single proposed-game slot — queued or already played. {@code identity} is the "which game" key
 * (art cache + cooldown); {@code gameInstanceId} is the per-appearance id minted at creation and
 * regenerated on replace (FR-034), and is the binding target for upvotes (FR-030). {@code position}
 * is set only while {@code QUEUED}; {@code playedWeek} only once {@code PLAYED}.
 *
 * <p>Pure domain type — no framework imports.
 */
public record QueueSlot(
    long id,
    long guildId,
    long proposerMemberId,
    CapturedGame game,
    GameIdentity identity,
    UUID gameInstanceId,
    Integer position,
    QueueStatus status,
    int coinsSpent,
    int upvoteCount,
    Integer playedWeek) {

  public boolean isQueued() {
    return status == QueueStatus.QUEUED;
  }
}
