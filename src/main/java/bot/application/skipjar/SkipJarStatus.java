package bot.application.skipjar;

import java.time.Instant;

/**
 * The current game's skip-jar status (US3). {@code gameName} is null only when {@code NO_GAME};
 * {@code count}/{@code threshold}/{@code remaining}/{@code earnerCount}/{@code floor} are
 * meaningful when {@code OPEN}; {@code opensAt} (became-current + dwell) is meaningful when {@code
 * NOT_OPEN}.
 */
public record SkipJarStatus(
    State state,
    String gameName,
    int count,
    int threshold,
    int remaining,
    int earnerCount,
    int floor,
    Instant opensAt) {

  public enum State {
    NO_GAME,
    NOT_OPEN,
    OPEN
  }
}
