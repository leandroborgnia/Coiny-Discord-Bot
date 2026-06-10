package bot.discord.command;

import bot.domain.DomainException;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Resolves coin i18n keys (and {@link DomainException}s) against the message bundle. Keeps the thin
 * handlers free of hard-coded user-facing copy.
 */
@Component
public class CoinMessages {

  private final MessageSource messageSource;

  public CoinMessages(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  public String get(String key, Object... args) {
    return messageSource.getMessage(key, args, Locale.getDefault());
  }

  public String error(DomainException exception) {
    return messageSource.getMessage(exception.messageKey(), exception.args(), Locale.getDefault());
  }
}
