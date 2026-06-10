package bot.domain.coin;

import bot.domain.DomainException;

/**
 * Thrown when an adjustment is attempted in a server that has not designated a moderator role. The
 * feature fails closed: with no configured role, no one may grant or deduct.
 */
public class ModeratorRoleNotConfiguredException extends DomainException {

  public ModeratorRoleNotConfiguredException() {
    super("coin.error.role-not-configured");
  }
}
