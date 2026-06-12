package bot.domain.queue;

import java.util.UUID;

/**
 * Carrier for appending a new queue slot. Carries a freshly-minted {@code gameInstanceId} and the
 * {@code proposeInteractionId} used for at-most-once idempotency ({@code ON CONFLICT DO NOTHING}).
 * A bootstrap instant-pop uses {@code status = PLAYED} with {@code position = null} and {@code
 * playedWeek} set; a normal proposal is {@code QUEUED} at the given tail {@code position}.
 */
public record NewSlot(
    long guildId,
    long proposerMemberId,
    CapturedGame game,
    GameIdentity identity,
    UUID gameInstanceId,
    Integer position,
    QueueStatus status,
    int coinsSpent,
    Integer playedWeek,
    long proposeInteractionId) {

  /** A normal queued proposal at the tail. */
  public static NewSlot queued(
      long guildId,
      long proposerMemberId,
      CapturedGame game,
      GameIdentity identity,
      UUID gameInstanceId,
      int position,
      int coinsSpent,
      long proposeInteractionId) {
    return new NewSlot(
        guildId,
        proposerMemberId,
        game,
        identity,
        gameInstanceId,
        position,
        QueueStatus.QUEUED,
        coinsSpent,
        null,
        proposeInteractionId);
  }

  /** A bootstrap instant-pop: created already PLAYED for the given week (FR-024). */
  public static NewSlot instantPopped(
      long guildId,
      long proposerMemberId,
      CapturedGame game,
      GameIdentity identity,
      UUID gameInstanceId,
      int coinsSpent,
      int playedWeek,
      long proposeInteractionId) {
    return new NewSlot(
        guildId,
        proposerMemberId,
        game,
        identity,
        gameInstanceId,
        null,
        QueueStatus.PLAYED,
        coinsSpent,
        playedWeek,
        proposeInteractionId);
  }
}
