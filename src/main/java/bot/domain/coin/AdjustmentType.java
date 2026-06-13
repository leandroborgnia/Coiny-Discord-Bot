package bot.domain.coin;

/**
 * The economic-event type recorded on a movement header. {@code GRANT}/{@code DEDUCTION} are the
 * moderator-chosen coin operations (forfeiture is a consequence of a grant, not a selectable type).
 * {@code QUEUE_PROPOSE}/{@code QUEUE_BUMP}/{@code QUEUE_REFUND} are the game-queue spends and the
 * withdraw refund (feature 004), posted through the same append-only ledger against the {@code POT}
 * account. {@code PARTICIPATION} is the earned coin drop (feature 005), credited through the same
 * ledger as a balanced grant (TREASURY→MEMBER, plus TREASURY→FORFEIT over the cap). {@code
 * SKIP_JAR} is the one non-refundable coin a member pays to vote a game off early (feature 006),
 * posted through the same ledger as a balanced spend against the {@code SKIP_POT} account. These
 * values are persisted by name and mirrored by the {@code coin_movement} type CHECK.
 */
public enum AdjustmentType {
  GRANT,
  DEDUCTION,
  QUEUE_PROPOSE,
  QUEUE_BUMP,
  QUEUE_REFUND,
  PARTICIPATION,
  SKIP_JAR
}
