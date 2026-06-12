package bot.discord.command;

import static org.assertj.core.api.Assertions.assertThat;

import bot.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Guards the command surface (FR-006, SC-006): the registered {@link SlashCommandHandler} beans are
 * exactly the non-transferring set, so a future coin-transfer/gift/trade command between members
 * cannot be added silently. Coins are mine alone — no command moves them from one member to
 * another.
 */
class CommandSurfaceTest extends AbstractPostgresIntegrationTest {

  @Autowired private List<SlashCommandHandler> handlers;

  @Test
  void registeredCommandSurfaceIsExactlyTheNonTransferringSet() {
    Set<String> names =
        handlers.stream().map(SlashCommandHandler::name).collect(Collectors.toSet());

    assertThat(names)
        .containsExactlyInAnyOrder(
            "ping",
            "balance",
            "coins-adjust",
            "coins-config",
            "queue-propose",
            "queue-withdraw",
            "queue-view",
            "queue-bump");
  }

  @Test
  void noCommandNameSuggestsMemberToMemberTransfer() {
    assertThat(handlers)
        .extracting(SlashCommandHandler::name)
        .noneMatch(name -> name.matches(".*(transfer|gift|trade|send|pay).*"));
  }
}
