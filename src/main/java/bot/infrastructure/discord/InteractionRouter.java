package bot.infrastructure.discord;

import bot.discord.command.SlashCommandHandler;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Routes each slash-command interaction to the matching {@link SlashCommandHandler} bean. Logs the
 * originating server (guild) and channel so every interaction is traceable to the server it came
 * from; the handler then replies in that same server and channel.
 */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InteractionRouter extends ListenerAdapter {

  private static final Logger log = LoggerFactory.getLogger(InteractionRouter.class);

  private final Map<String, SlashCommandHandler> handlers;

  public InteractionRouter(List<SlashCommandHandler> handlers) {
    this.handlers =
        handlers.stream().collect(Collectors.toMap(SlashCommandHandler::name, Function.identity()));
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    String guild = event.isFromGuild() ? event.getGuild().getId() : "DM";
    log.info(
        "Received /{} from guild={} channel={} user={}",
        event.getName(),
        guild,
        event.getChannelId(),
        event.getUser().getId());

    SlashCommandHandler handler = handlers.get(event.getName());
    if (handler != null) {
      handler.handle(event);
    } else {
      log.warn("No handler registered for command /{}", event.getName());
    }
  }
}
