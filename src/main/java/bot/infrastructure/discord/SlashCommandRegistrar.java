package bot.infrastructure.discord;

import bot.discord.command.SlashCommandHandler;
import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Upserts the registered slash commands when the gateway is ready. Prefers fast guild-scoped
 * registration against the configured test guild; falls back to global registration if no usable
 * guild id is configured.
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
  private final DiscordProperties properties;

  public SlashCommandRegistrar(List<SlashCommandHandler> handlers, DiscordProperties properties) {
    this.handlers = handlers;
    this.properties = properties;
  }

  @Override
  public void onReady(ReadyEvent event) {
    List<CommandData> commands = handlers.stream().map(SlashCommandHandler::commandData).toList();
    String guildId = properties.guildId();
    if (guildId != null && !guildId.isBlank()) {
      Guild guild = event.getJDA().getGuildById(guildId);
      if (guild != null) {
        guild.updateCommands().addCommands(commands).queue();
        log.info("Registered {} guild command(s) to guild {}", commands.size(), guildId);
        return;
      }
      log.warn("Configured guild {} not found; registering globally instead", guildId);
    }
    event.getJDA().updateCommands().addCommands(commands).queue();
    log.info("Registered {} global command(s)", commands.size());
  }
}
