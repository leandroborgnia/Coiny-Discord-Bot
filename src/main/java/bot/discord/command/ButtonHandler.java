package bot.discord.command;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/**
 * SPI for a button interaction handler. Implementations are thin inbound adapters dispatched by
 * {@code ButtonInteractionRouter} on a component-id prefix match — they acknowledge the
 * interaction, delegate to an application service, and (for the upvote) trigger the announcement
 * edit; no business logic.
 */
public interface ButtonHandler {

  /** The component-id prefix this handler claims, e.g. {@code "upvote:"}. */
  String prefix();

  /** Handle a button click whose component id starts with {@link #prefix()}. */
  void handle(ButtonInteractionEvent event);
}
