package bot.discord.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Guards user-facing rendering: a Discord user id embedded in a {@code <@id>} mention must NOT be
 * run through locale number-grouping (which inserts commas like {@code 733,546,...} and breaks the
 * mention so Discord shows the raw id instead of the member's name).
 */
class CoinMessagesTest {

  private static final long MEMBER_ID = 733546526231494727L;

  private final CoinMessages messages = new CoinMessages(bundle());

  private static ResourceBundleMessageSource bundle() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("messages/coin-messages");
    source.setDefaultEncoding("UTF-8");
    return source;
  }

  @Test
  void grantReplyRendersAResolvableMentionWithoutGroupingCommas() {
    String rendered = messages.get("coin.reply.granted", 10, MEMBER_ID, 10, 12);

    assertThat(rendered).contains("<@" + MEMBER_ID + ">").doesNotContain(",");
  }

  @Test
  void deductHistoryAndOverdrawMentionsAreNotNumberGrouped() {
    assertThat(messages.get("coin.reply.deducted", 5, MEMBER_ID, 5, 12))
        .contains("<@" + MEMBER_ID + ">")
        .doesNotContain(",");
    assertThat(messages.get("coin.reply.history.grant", 5, MEMBER_ID, ""))
        .contains("<@" + MEMBER_ID + ">");
    assertThat(messages.error(new bot.domain.coin.OverdrawException(MEMBER_ID, 3)))
        .contains("<@" + MEMBER_ID + ">");
  }
}
