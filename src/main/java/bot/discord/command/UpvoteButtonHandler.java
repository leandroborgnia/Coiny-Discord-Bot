package bot.discord.command;

import bot.application.queue.ToggleUpvoteRequest;
import bot.application.queue.ToggleUpvoteResult;
import bot.application.queue.UpvoteService;
import bot.domain.queue.AnnouncementPort;
import java.util.UUID;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Thin upvote button handler (US5). Component id is {@code upvote:{slotId}:{gameInstanceId}}. Acks
 * with {@code deferEdit()} so the ephemeral view is NOT re-rendered (its counts stay a snapshot,
 * FR-029), delegates to {@link UpvoteService}, and — only when the vote actually changed and a live
 * announcement message exists — edits that single message (FR-038). Gated by {@code
 * discord.enabled}.
 */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class UpvoteButtonHandler implements ButtonHandler {

  private final UpvoteService upvoteService;
  private final AnnouncementPort announcementPort;

  public UpvoteButtonHandler(UpvoteService upvoteService, AnnouncementPort announcementPort) {
    this.upvoteService = upvoteService;
    this.announcementPort = announcementPort;
  }

  @Override
  public String prefix() {
    return "upvote:";
  }

  @Override
  public void handle(ButtonInteractionEvent event) {
    event.deferEdit().queue(); // ack without touching the ephemeral message

    String[] parts = event.getComponentId().split(":");
    if (parts.length < 3) {
      return;
    }
    long slotId = Long.parseLong(parts[1]);
    UUID gameInstanceId = UUID.fromString(parts[2]);

    ToggleUpvoteResult result =
        upvoteService.toggle(
            new ToggleUpvoteRequest(
                event.getGuild().getIdLong(),
                event.getUser().getIdLong(),
                slotId,
                gameInstanceId,
                event.getIdLong()));

    result
        .liveSurface()
        .ifPresent(
            ref ->
                result
                    .announcementView()
                    .ifPresent(
                        view ->
                            announcementPort.edit(
                                ref.guildId(), ref.channelId(), ref.messageId(), view)));
  }
}
