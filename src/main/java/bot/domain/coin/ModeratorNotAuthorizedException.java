package bot.domain.coin;

import bot.domain.DomainException;

/**
 * Thrown when the caller is not authorized for a coin action — they lack the server's configured
 * moderator role (adjustments) or administrator permission (configuration). The message key
 * distinguishes the two cases.
 */
public class ModeratorNotAuthorizedException extends DomainException {

  public ModeratorNotAuthorizedException(String messageKey) {
    super(messageKey);
  }

  /** Caller lacks the configured moderator role. */
  public static ModeratorNotAuthorizedException missingRole() {
    return new ModeratorNotAuthorizedException("coin.error.not-authorized");
  }

  /** Caller is not a server administrator (configuration action). */
  public static ModeratorNotAuthorizedException notAdmin() {
    return new ModeratorNotAuthorizedException("coin.error.config-not-admin");
  }
}
