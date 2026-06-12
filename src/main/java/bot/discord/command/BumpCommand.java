package bot.discord.command;

import bot.application.queue.BumpGameRequest;
import bot.application.queue.BumpGameResult;
import bot.application.queue.BumpGameService;
import bot.domain.DomainException;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /queue-bump} handler (US4). Defers ephemerally, delegates to {@link BumpGameService},
 * and renders the outcome. Acts only on the caller's own queued slot.
 */
@Component
public class BumpCommand implements SlashCommandHandler {

  private final BumpGameService bumpGameService;
  private final QueueMessages messages;

  public BumpCommand(BumpGameService bumpGameService, QueueMessages messages) {
    this.bumpGameService = bumpGameService;
    this.messages = messages;
  }

  @Override
  public String name() {
    return "queue-bump";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(name(), "Spend coins to move your queued game up one position.")
        .setGuildOnly(true);
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    BumpGameRequest request =
        new BumpGameRequest(
            event.getGuild().getIdLong(), event.getUser().getIdLong(), event.getIdLong());

    try {
      BumpGameResult result = bumpGameService.bump(request);
      event.getHook().editOriginal(render(result)).queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }

  private String render(BumpGameResult result) {
    return switch (result.outcome()) {
      case BUMPED -> messages.get("queue.reply.bumped", result.newPosition(), result.coinsSpent());
      case AT_TOP -> messages.get("queue.error.at-top");
      case NO_QUEUED -> messages.get("queue.error.no-queued");
      case DUPLICATE -> messages.get("queue.reply.duplicate");
    };
  }
}
