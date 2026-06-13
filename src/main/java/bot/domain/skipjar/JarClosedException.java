package bot.domain.skipjar;

import bot.domain.DomainException;
import java.time.Instant;

/**
 * Thrown when a contribution is attempted before the current game's dwell time has elapsed — the
 * jar is not open yet (FR-007). Carries the game's display name and the instant the jar opens for
 * the rendered message; throwing rolls the transaction back so no coin is charged.
 */
public class JarClosedException extends DomainException {

  private final transient Instant opensAt;

  public JarClosedException(String gameName, Instant opensAt) {
    super("skip.error.jar-closed", gameName, opensAt.getEpochSecond());
    this.opensAt = opensAt;
  }

  public Instant opensAt() {
    return opensAt;
  }
}
