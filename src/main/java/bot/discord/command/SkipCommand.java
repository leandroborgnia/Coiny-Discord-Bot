package bot.discord.command;

import bot.application.skipjar.ContributeRequest;
import bot.application.skipjar.ContributeResult;
import bot.application.skipjar.ContributeToSkipJarService;
import bot.application.skipjar.SkipJarStatus;
import bot.application.skipjar.ViewRequest;
import bot.application.skipjar.ViewSkipJarService;
import bot.domain.DomainException;
import bot.domain.coin.OverdrawException;
import bot.infrastructure.discord.AnnouncementPoster;
import java.time.Instant;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /skip} handler (any member): {@code contribute} casts a one-coin vote into the
 * current game's skip jar (US1/US2). Defers ephemerally FIRST (Principle V), then delegates to
 * {@link ContributeToSkipJarService} and renders the success / skip-triggered / refusal reply. When
 * a contribution triggers the early skip and an announcement channel is configured, the regular
 * rotation announcement is posted after the transaction commits.
 */
@Component
public class SkipCommand implements SlashCommandHandler {

  private final ContributeToSkipJarService contributeService;
  private final ViewSkipJarService viewService;
  private final CoinMessages messages;
  // Optional: absent when Discord is disabled (tests). Used to post the post-skip announcement.
  private final ObjectProvider<AnnouncementPoster> announcementPoster;

  public SkipCommand(
      ContributeToSkipJarService contributeService,
      ViewSkipJarService viewService,
      CoinMessages messages,
      ObjectProvider<AnnouncementPoster> announcementPoster) {
    this.contributeService = contributeService;
    this.viewService = viewService;
    this.messages = messages;
    this.announcementPoster = announcementPoster;
  }

  @Override
  public String name() {
    return "skip";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(
            name(), "Vote to retire the current game early by paying into its skip jar.")
        .setGuildOnly(true)
        .addSubcommands(
            new SubcommandData(
                "contribute", "Pay one coin into the current game's skip jar to vote to skip it."),
            new SubcommandData("status", "View the current game's skip jar (count, threshold)."));
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    long guildId = event.getGuild().getIdLong();
    long memberId = event.getUser().getIdLong();
    long interactionId = event.getIdLong();
    String sub = event.getSubcommandName();

    if ("status".equals(sub)) {
      SkipJarStatus status = viewService.view(new ViewRequest(guildId, Instant.now()));
      event.getHook().editOriginal(renderStatus(status)).queue();
      return;
    }

    if (!"contribute".equals(sub)) {
      event.getHook().editOriginal(messages.get("skip.error.no-game")).queue();
      return;
    }

    try {
      ContributeResult result =
          contributeService.contribute(
              new ContributeRequest(guildId, memberId, interactionId, Instant.now()));
      event.getHook().editOriginal(renderContribute(result)).queue();
      // Post the rotation announcement (if any) after the contribution transaction has committed.
      result
          .announcement()
          .ifPresent(view -> announcementPoster.ifAvailable(poster -> poster.post(guildId, view)));
    } catch (OverdrawException e) {
      event.getHook().editOriginal(messages.get("skip.error.insufficient")).queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }

  private String renderStatus(SkipJarStatus status) {
    return switch (status.state()) {
      case NO_GAME -> messages.get("skip.reply.status.no-game");
      case NOT_OPEN ->
          messages.get(
              "skip.reply.status.not-open", status.gameName(), status.opensAt().getEpochSecond());
      case OPEN ->
          messages.get(
              "skip.reply.status.open",
              status.gameName(),
              status.count(),
              status.threshold(),
              status.remaining(),
              status.earnerCount(),
              status.floor());
    };
  }

  private String renderContribute(ContributeResult result) {
    if (result.skipped()) {
      return messages.get("skip.reply.contribute.skipped", result.gameName(), result.newGameName());
    }
    return messages.get(
        "skip.reply.contribute.success",
        result.count(),
        result.threshold(),
        result.remaining(),
        result.gameName());
  }
}
