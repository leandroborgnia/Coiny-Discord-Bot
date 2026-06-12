package bot.infrastructure.discord;

import bot.discord.command.ButtonHandler;
import java.util.List;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Routes each button interaction to the first {@link ButtonHandler} whose prefix matches the
 * component id (mirrors {@code InteractionRouter} for slash commands). Registered as a JDA
 * listener. Gated by {@code discord.enabled}, so tests neither route nor need JDA.
 */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ButtonInteractionRouter extends ListenerAdapter {

  private static final Logger log = LoggerFactory.getLogger(ButtonInteractionRouter.class);

  private final List<ButtonHandler> handlers;

  public ButtonInteractionRouter(List<ButtonHandler> handlers) {
    this.handlers = handlers;
  }

  @Override
  public void onButtonInteraction(ButtonInteractionEvent event) {
    String componentId = event.getComponentId();
    handlers.stream()
        .filter(handler -> componentId.startsWith(handler.prefix()))
        .findFirst()
        .ifPresentOrElse(
            handler -> handler.handle(event),
            () -> log.warn("No button handler registered for component id {}", componentId));
  }
}
