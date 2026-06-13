package bot.application.skipjar;

import java.util.Set;

/**
 * A request to change one skip-jar setting (US4). {@code op} selects which field; the matching
 * value field carries the new value. {@code actorRoleIds}/{@code actorIsAdmin} drive the
 * moderator-role authorization (Administrator bypasses; fails closed when no role is configured).
 */
public record ConfigureRequest(
    long guildId,
    Op op,
    Set<Long> actorRoleIds,
    boolean actorIsAdmin,
    int floor, // op == FLOOR
    long dwellSeconds, // op == DWELL
    boolean gateOn) { // op == GATE

  public enum Op {
    FLOOR,
    DWELL,
    GATE
  }
}
