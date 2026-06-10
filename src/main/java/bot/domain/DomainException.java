package bot.domain;

/**
 * Base type for typed domain rule violations. Carries an i18n message key (and optional arguments)
 * that an inbound adapter resolves against the message bundle — domain code never holds user-facing
 * copy. Per the project error model, domain rules throw a {@code DomainException} subclass rather
 * than a raw {@link RuntimeException}.
 */
public abstract class DomainException extends RuntimeException {

  private final String messageKey;
  private final transient Object[] args;

  protected DomainException(String messageKey, Object... args) {
    super(messageKey);
    this.messageKey = messageKey;
    this.args = args == null ? new Object[0] : args.clone();
  }

  /** The i18n key the inbound adapter resolves to a localized message. */
  public String messageKey() {
    return messageKey;
  }

  /** Positional arguments for the resolved message template. */
  public Object[] args() {
    return args.clone();
  }
}
