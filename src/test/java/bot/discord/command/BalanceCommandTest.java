package bot.discord.command;

import static org.assertj.core.api.Assertions.assertThat;

import bot.application.coin.BalanceView.MovementSummary;
import bot.domain.coin.AdjustmentType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * US3: a participation earning renders in {@code /balance} history as a clearly-labelled credit
 * line (not the deduction fall-through), with the forfeiture suffix when the drop was
 * cap-truncated.
 */
class BalanceCommandTest {

  private static final long MEMBER = 733_000_111_222_333L;

  private final BalanceCommand command = new BalanceCommand(null, new CoinMessages(bundle()), 10);

  private static ResourceBundleMessageSource bundle() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("messages/coin-messages");
    source.setDefaultEncoding("UTF-8");
    return source;
  }

  @Test
  void participationRendersAsCreditLine() {
    String line = command.historyLine(participation(3, 0));

    assertThat(line).startsWith("+3");
    assertThat(line).contains("participation");
    assertThat(line).doesNotContain("deducted");
    assertThat(line).doesNotContain("forfeited");
  }

  @Test
  void capTruncatedParticipationShowsForfeitedSuffix() {
    String line = command.historyLine(participation(2, 5));

    assertThat(line).startsWith("+2");
    assertThat(line).contains("participation");
    assertThat(line).contains("5 forfeited");
  }

  private static MovementSummary participation(int credited, int forfeited) {
    // A participation drop carries the member's own id as the "moderator" (self-credited).
    return new MovementSummary(
        AdjustmentType.PARTICIPATION,
        credited + forfeited,
        credited,
        forfeited,
        null,
        MEMBER,
        Instant.now());
  }
}
