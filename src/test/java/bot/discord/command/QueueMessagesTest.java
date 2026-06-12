package bot.discord.command;

import static org.assertj.core.api.Assertions.assertThat;

import bot.domain.queue.InsufficientCoinsException;
import bot.domain.queue.NotEligibleException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Verifies every US1 reply/error key resolves against the queue bundle (a missing key throws), and
 * that embedded member-id mentions are not number-grouped (commas would break the mention).
 */
class QueueMessagesTest {

  private final QueueMessages messages = new QueueMessages(bundle());

  private static ResourceBundleMessageSource bundle() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("messages/queue-messages");
    source.setDefaultEncoding("UTF-8");
    return source;
  }

  @Test
  void everyUs1ReplyKeyResolves() {
    assertThat(messages.get("queue.reply.proposed", "Hades", 3, 1)).contains("Hades");
    assertThat(messages.get("queue.reply.instant-pop", "Hades", 1)).contains("Hades");
    assertThat(messages.get("queue.reply.replaced", "Hades", 2)).contains("Hades");
    assertThat(messages.get("queue.reply.duplicate")).isNotEmpty();
    assertThat(messages.get("queue.reply.withdrawn", 3)).contains("3");
  }

  @Test
  void everyUs1ErrorKeyResolves() {
    assertThat(messages.get("queue.error.no-activity")).isNotEmpty();
    assertThat(messages.error(new InsufficientCoinsException(0))).isNotEmpty();
    assertThat(messages.error(new NotEligibleException(2))).contains("2");
    assertThat(messages.get("queue.error.no-queued")).isNotEmpty();
    assertThat(messages.get("queue.error.not-authorized")).isNotEmpty();
  }
}
