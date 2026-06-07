package bot.infrastructure.discord;

import bot.discord.command.SlashCommandHandler;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Routes each slash-command interaction to the matching {@link SlashCommandHandler} bean. */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InteractionRouter extends ListenerAdapter {

  private final Map<String, SlashCommandHandler> handlers;

  public InteractionRouter(List<SlashCommandHandler> handlers) {
    this.handlers =
        handlers.stream().collect(Collectors.toMap(SlashCommandHandler::name, Function.identity()));
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    SlashCommandHandler handler = handlers.get(event.getName());
    if (handler != null) {
      handler.handle(event);
    }
  }
}
