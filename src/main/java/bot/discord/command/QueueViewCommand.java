package bot.discord.command;

import bot.application.queue.ViewQueueRequest;
import bot.application.queue.ViewQueueService;
import bot.domain.DomainException;
import bot.domain.queue.QueueView;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /queue-view} handler (US3). Defers ephemerally, delegates to {@link
 * ViewQueueService}, and renders the current game (with key art) plus the next five queued games
 * (key art via the embed, proposer, position, snapshot upvote count). The viewer's own queued game
 * is always shown and marked, even beyond the top five. Each queued slot carries an upvote button
 * whose id encodes the slot's current appearance ({@code upvote:slotId:gameInstanceId}); the button
 * handler arrives in US5.
 */
@Component
public class QueueViewCommand implements SlashCommandHandler {

  private final ViewQueueService viewQueueService;
  private final QueueMessages messages;

  public QueueViewCommand(ViewQueueService viewQueueService, QueueMessages messages) {
    this.viewQueueService = viewQueueService;
    this.messages = messages;
  }

  @Override
  public String name() {
    return "queue-view";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(name(), "View the current game and the queue.").setGuildOnly(true);
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    try {
      QueueView view =
          viewQueueService.view(
              new ViewQueueRequest(event.getGuild().getIdLong(), event.getUser().getIdLong()));
      event.getHook().editOriginalEmbeds(buildEmbed(view)).setComponents(buttonRows(view)).queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }

  private net.dv8tion.jda.api.entities.MessageEmbed buildEmbed(QueueView view) {
    EmbedBuilder embed = new EmbedBuilder();
    view.currentGame()
        .ifPresentOrElse(
            current -> {
              embed.setTitle(
                  messages.get(
                      "queue.reply.view.current", current.gameName(), current.proposerId()));
              if (current.coverUrl() != null && !current.coverUrl().isBlank()) {
                embed.setImage(current.coverUrl());
              }
            },
            () -> embed.setTitle(messages.get("queue.reply.view.no-current")));

    embed.setDescription(describeQueue(view));
    embed.setFooter(
        view.eligibleToPropose()
            ? messages.get("queue.reply.view.eligible")
            : messages.get("queue.reply.view.cooldown", view.gamesRemaining()));
    return embed.build();
  }

  private String describeQueue(QueueView view) {
    if (view.upNext().isEmpty() && view.ownEntry().isEmpty()) {
      return messages.get("queue.reply.view.empty");
    }
    StringBuilder body = new StringBuilder();
    for (QueueView.Entry entry : view.upNext()) {
      body.append(renderEntry(entry, isOwn(view, entry))).append('\n');
    }
    if (view.ownEntry().isPresent() && !view.ownEntryShownInUpNext()) {
      body.append("…\n").append(renderEntry(view.ownEntry().get(), true)).append('\n');
    }
    return body.toString();
  }

  private String renderEntry(QueueView.Entry entry, boolean own) {
    String line =
        messages.get(
            "queue.reply.view.entry",
            entry.position(),
            entry.gameName(),
            entry.proposerId(),
            entry.upvoteCount());
    return own ? line + messages.get("queue.reply.view.own-marker") : line;
  }

  private static boolean isOwn(QueueView view, QueueView.Entry entry) {
    return view.ownEntryShownInUpNext()
        && view.ownEntry().isPresent()
        && view.ownEntry().get().slotId() == entry.slotId();
  }

  private static List<ActionRow> buttonRows(QueueView view) {
    List<Button> buttons = new ArrayList<>();
    for (QueueView.Entry entry : view.upNext()) {
      buttons.add(upvoteButton(entry));
    }
    if (view.ownEntry().isPresent() && !view.ownEntryShownInUpNext()) {
      buttons.add(upvoteButton(view.ownEntry().get()));
    }
    List<ActionRow> rows = new ArrayList<>();
    for (int i = 0; i < buttons.size(); i += 5) {
      rows.add(ActionRow.of(buttons.subList(i, Math.min(i + 5, buttons.size()))));
    }
    return rows;
  }

  private static Button upvoteButton(QueueView.Entry entry) {
    String id = "upvote:" + entry.slotId() + ":" + entry.gameInstanceId();
    return Button.secondary(id, "👍 " + entry.upvoteCount());
  }
}
