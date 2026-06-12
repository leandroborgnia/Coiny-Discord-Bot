package bot.application.queue;

/**
 * Outcome of a propose. {@code PROPOSED} (added at {@code position}); {@code INSTANT_POPPED}
 * (became this week's game); {@code REPLACED} (updated the existing slot, free); {@code DUPLICATE}
 * (same interaction already applied); {@code NO_ACTIVITY} (no readable game — nothing charged or
 * changed). Affordability and cooldown failures are thrown as typed {@code DomainException}s, not
 * returned.
 */
public record ProposeGameResult(
    Outcome outcome, int position, boolean instantPop, int coinsSpent, int newBalance) {

  public enum Outcome {
    PROPOSED,
    INSTANT_POPPED,
    REPLACED,
    DUPLICATE,
    NO_ACTIVITY
  }
}
