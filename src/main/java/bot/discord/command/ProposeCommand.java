package bot.discord.command;

import bot.application.queue.ProposeGameRequest;
import bot.application.queue.ProposeGameResult;
import bot.application.queue.ProposeGameService;
import bot.domain.DomainException;
import bot.domain.queue.CapturedGame;
import bot.infrastructure.discord.AnnouncementPoster;
import bot.infrastructure.discord.PresenceReader;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /queue-propose} handler (US1). Defers ephemerally FIRST, then does the blocking
 * Rich-Presence capture and the service call **off the JDA gateway thread** — the capture's {@code
 * retrieveMembersByIds(...).get()} would otherwise deadlock the very thread that delivers the
 * member-presence chunk. After the defer, the 2.5s ack deadline is met (Principle V). Any member
 * currently playing a game may propose; no readable activity yields the no-activity guidance.
 */
@Component
public class ProposeCommand implements SlashCommandHandler {

  private static final Logger log = LoggerFactory.getLogger(ProposeCommand.class);

  private final ProposeGameService proposeGameService;
  private final PresenceReader presenceReader;
  private final QueueMessages messages;
  // Optional: absent when Discord is disabled (tests). Used to post the instant-pop announcement.
  private final ObjectProvider<AnnouncementPoster> announcementPoster;
  private final ExecutorService worker =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "queue-propose-worker");
            thread.setDaemon(true);
            return thread;
          });

  public ProposeCommand(
      ProposeGameService proposeGameService,
      PresenceReader presenceReader,
      QueueMessages messages,
      ObjectProvider<AnnouncementPoster> announcementPoster) {
    this.proposeGameService = proposeGameService;
    this.presenceReader = presenceReader;
    this.messages = messages;
    this.announcementPoster = announcementPoster;
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

    Guild guild = event.getGuild();
    long memberId = event.getUser().getIdLong();
    long interactionId = event.getIdLong();
    // Offload the blocking presence fetch + service call off the gateway read thread so the member
    // chunk can be delivered (see PresenceReader). editOriginal via the hook works from any thread.
    worker.execute(() -> process(event, guild, memberId, interactionId));
  }

  private void process(
      SlashCommandInteractionEvent event, Guild guild, long memberId, long interactionId) {
    try {
      Optional<CapturedGame> captured = presenceReader.capture(guild, memberId);
      ProposeGameRequest request =
          new ProposeGameRequest(guild.getIdLong(), memberId, captured.orElse(null), interactionId);
      try {
        ProposeGameResult result = proposeGameService.propose(request);
        event.getHook().editOriginal(render(result, captured)).queue();
        // Post the instant-pop announcement (if any) after the propose transaction has committed.
        result
            .announcement()
            .ifPresent(
                view ->
                    announcementPoster.ifAvailable(poster -> poster.post(guild.getIdLong(), view)));
      } catch (DomainException e) {
        event.getHook().editOriginal(messages.error(e)).queue();
      }
    } catch (RuntimeException e) {
      log.error("Unexpected failure handling /queue-propose for member {}", memberId, e);
      event.getHook().editOriginal(messages.get("queue.error.unexpected")).queue();
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
