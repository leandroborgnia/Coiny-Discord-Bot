package bot.discord.command;

import bot.domain.DomainException;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Resolves game-queue i18n keys (and {@link DomainException}s) against the message bundle
 * (messages/queue-messages). Mirrors {@code CoinMessages}, keeping the thin queue handlers free of
 * hard-coded user-facing copy.
 */
@Component
public class QueueMessages {

  private final MessageSource messageSource;

  public QueueMessages(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  public String get(String key, Object... args) {
    return messageSource.getMessage(key, args, Locale.getDefault());
  }

  public String error(DomainException exception) {
    return messageSource.getMessage(exception.messageKey(), exception.args(), Locale.getDefault());
  }
}
