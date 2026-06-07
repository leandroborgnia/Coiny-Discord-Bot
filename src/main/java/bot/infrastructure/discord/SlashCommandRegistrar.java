package bot.infrastructure.discord;

import bot.discord.command.SlashCommandHandler;
import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the slash commands to every server the bot is in. On startup each guild the bot is
 * already a member of gets the commands upserted (instant availability); when the bot later joins a
 * new server, that server is registered too. There is no single configured guild — the bot is
 * multi-server by design, and each interaction is handled in the server it came from.
 */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SlashCommandRegistrar extends ListenerAdapter {

  private static final Logger log = LoggerFactory.getLogger(SlashCommandRegistrar.class);

  private final List<SlashCommandHandler> handlers;

  public SlashCommandRegistrar(List<SlashCommandHandler> handlers) {
    this.handlers = handlers;
  }

  @Override
  public void onReady(ReadyEvent event) {
    List<CommandData> commands = commandData();
    List<Guild> guilds = event.getJDA().getGuilds();
    guilds.forEach(guild -> register(guild, commands));
    log.info("Registered {} command(s) to {} guild(s) on startup", commands.size(), guilds.size());
  }

  @Override
  public void onGuildJoin(GuildJoinEvent event) {
    Guild guild = event.getGuild();
    register(guild, commandData());
    log.info("Registered commands to newly-joined guild {} ({})", guild.getId(), guild.getName());
  }

  private void register(Guild guild, List<CommandData> commands) {
    guild.updateCommands().addCommands(commands).queue();
  }

  private List<CommandData> commandData() {
    return handlers.stream().map(SlashCommandHandler::commandData).toList();
  }
}
