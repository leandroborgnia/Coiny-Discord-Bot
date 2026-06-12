package bot.discord.command;

import bot.application.queue.ProposeGameRequest;
import bot.application.queue.ProposeGameResult;
import bot.application.queue.ProposeGameService;
import bot.domain.DomainException;
import bot.domain.queue.CapturedGame;
import bot.infrastructure.discord.PresenceReader;
import java.util.Optional;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /queue-propose} handler (US1). Defers ephemerally FIRST, then captures the member's
 * current game from their Rich Presence (on-demand fetch — after the defer, so the 2.5s ack
 * deadline is met, Principle V), delegates to {@link ProposeGameService}, and renders the outcome.
 * Any member currently playing a game may propose; no readable activity yields the no-activity
 * guidance.
 */
@Component
public class ProposeCommand implements SlashCommandHandler {

  private final ProposeGameService proposeGameService;
  private final PresenceReader presenceReader;
  private final QueueMessages messages;

  public ProposeCommand(
      ProposeGameService proposeGameService,
      PresenceReader presenceReader,
      QueueMessages messages) {
    this.proposeGameService = proposeGameService;
    this.presenceReader = presenceReader;
    this.messages = messages;
  }

  @Override
  public String name() {
    return "queue-propose";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(name(), "Propose the game you're currently playing to the queue.")
        .setGuildOnly(true);
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    long guildId = event.getGuild().getIdLong();
    long memberId = event.getUser().getIdLong();
    Optional<CapturedGame> captured = presenceReader.capture(event.getGuild(), memberId);

    ProposeGameRequest request =
        new ProposeGameRequest(guildId, memberId, captured.orElse(null), event.getIdLong());

    try {
      ProposeGameResult result = proposeGameService.propose(request);
      event.getHook().editOriginal(render(result, captured)).queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }

  private String render(ProposeGameResult result, Optional<CapturedGame> captured) {
    String game = captured.map(CapturedGame::name).orElse("");
    return switch (result.outcome()) {
      case NO_ACTIVITY -> messages.get("queue.error.no-activity");
      case DUPLICATE -> messages.get("queue.reply.duplicate");
      case REPLACED -> messages.get("queue.reply.replaced", game, result.position());
      case INSTANT_POPPED -> messages.get("queue.reply.instant-pop", game, result.coinsSpent());
      case PROPOSED ->
          messages.get("queue.reply.proposed", game, result.position(), result.coinsSpent());
    };
  }
}
