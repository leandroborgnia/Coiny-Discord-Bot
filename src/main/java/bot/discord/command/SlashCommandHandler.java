package bot.discord.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

/**
 * SPI for a single slash command. Implementations are thin inbound adapters: they acknowledge the
 * interaction, delegate to an application service, and render the reply — no business logic.
 */
public interface SlashCommandHandler {

  /** The command name, e.g. {@code "ping"}. */
  String name();

  /** The command definition used to register the command with Discord. */
  CommandData commandData();

  /** Handle an invocation. Must defer before doing any work that could exceed Discord's window. */
  void handle(SlashCommandInteractionEvent event);
}
