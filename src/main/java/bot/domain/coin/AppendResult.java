package bot.domain.coin;

/**
 * Outcome of appending a movement. {@code inserted} is false when the interaction id already
 * existed (an idempotent no-op), in which case {@code movement} is the originally recorded one.
 */
public record AppendResult(MovementRecord movement, boolean inserted) {}
