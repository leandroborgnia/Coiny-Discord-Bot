package bot.domain.coin;

/**
 * The economic-event type recorded on a movement header. {@code GRANT}/{@code DEDUCTION} are the
 * moderator-chosen coin operations (forfeiture is a consequence of a grant, not a selectable type).
 * {@code QUEUE_PROPOSE}/{@code QUEUE_BUMP}/{@code QUEUE_REFUND} are the game-queue spends and the
 * withdraw refund (feature 004), posted through the same append-only ledger against the {@code POT}
 * account. These values are persisted by name and mirrored by the {@code coin_movement} type CHECK.
 */
public enum AdjustmentType {
  GRANT,
  DEDUCTION,
  QUEUE_PROPOSE,
  QUEUE_BUMP,
  QUEUE_REFUND
}
