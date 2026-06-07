package bot.discord.command;

import bot.application.liveness.CheckLivenessRequest;
import bot.application.liveness.CheckLivenessResult;
import bot.application.liveness.LivenessService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /ping} handler. Defers immediately (well within Discord's interaction-response
 * window), delegates the liveness check to {@link LivenessService}, then edits the deferred reply.
 * Contains no business logic and opens no transaction.
 */
@Component
public class PingCommand implements SlashCommandHandler {

  private final LivenessService livenessService;

  public PingCommand(LivenessService livenessService) {
    this.livenessService = livenessService;
  }

  @Override
  public String name() {
    return "ping";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(
        name(), "Liveness check — confirms the bot is online and its data store is reachable.");
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply().queue();
    CheckLivenessResult result = livenessService.check(CheckLivenessRequest.instance());
    String message =
        result.reachable()
            ? "🟢 Pong! Data store reachable (" + result.detail() + ")."
            : "🔴 Pong, but the data store is not reachable right now.";
    event.getHook().editOriginal(message).queue();
  }
}
