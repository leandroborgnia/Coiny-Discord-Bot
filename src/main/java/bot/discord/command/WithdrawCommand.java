package bot.discord.command;

import bot.application.queue.WithdrawGameRequest;
import bot.application.queue.WithdrawGameResult;
import bot.application.queue.WithdrawGameService;
import bot.domain.DomainException;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /queue-withdraw} handler (US1). Defers ephemerally, delegates to {@link
 * WithdrawGameService}, and renders the refund outcome. Acts only on the caller's own queued slot.
 */
@Component
public class WithdrawCommand implements SlashCommandHandler {

  private final WithdrawGameService withdrawGameService;
  private final QueueMessages messages;

  public WithdrawCommand(WithdrawGameService withdrawGameService, QueueMessages messages) {
    this.withdrawGameService = withdrawGameService;
    this.messages = messages;
  }

  @Override
  public String name() {
    return "queue-withdraw";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(name(), "Withdraw your queued game and refund the coins you spent on it.")
        .setGuildOnly(true);
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    WithdrawGameRequest request =
        new WithdrawGameRequest(
            event.getGuild().getIdLong(), event.getUser().getIdLong(), event.getIdLong());

    try {
      WithdrawGameResult result = withdrawGameService.withdraw(request);
      event.getHook().editOriginal(render(result)).queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }

  private String render(WithdrawGameResult result) {
    return switch (result.outcome()) {
      case WITHDRAWN -> messages.get("queue.reply.withdrawn", result.refunded());
      case NO_QUEUED -> messages.get("queue.error.no-queued");
      case DUPLICATE -> messages.get("queue.reply.duplicate");
    };
  }
}
