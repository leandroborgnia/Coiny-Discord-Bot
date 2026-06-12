package bot.infrastructure.discord;

import bot.discord.command.QueueMessages;
import bot.domain.queue.AnnouncementPort;
import bot.domain.queue.AnnouncementView;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * JDA outbound adapter for the weekly-announcement live surface (FR-036/FR-038). Posts a new
 * announcement on a non-empty weekly advance and edits the single latest message on each upvote
 * change. All sends/edits run after the transaction commits, off the interaction ack (Principle V).
 * Gated by {@code discord.enabled}; absent in tests (no JDA).
 */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class JdaAnnouncementAdapter implements AnnouncementPort {

  private final JDA jda;
  private final QueueMessages messages;

  public JdaAnnouncementAdapter(JDA jda, QueueMessages messages) {
    this.jda = jda;
    this.messages = messages;
  }

  @Override
  public long post(long guildId, long channelId, AnnouncementView view) {
    return channel(channelId).sendMessageEmbeds(buildEmbed(view)).complete().getIdLong();
  }

  @Override
  public void edit(long guildId, long channelId, long messageId, AnnouncementView view) {
    channel(channelId).editMessageEmbedsById(messageId, buildEmbed(view)).queue();
  }

  private TextChannel channel(long channelId) {
    TextChannel channel = jda.getTextChannelById(channelId);
    if (channel == null) {
      throw new IllegalStateException("announcement channel " + channelId + " is not available");
    }
    return channel;
  }

  private MessageEmbed buildEmbed(AnnouncementView view) {
    EmbedBuilder embed =
        new EmbedBuilder()
            .setTitle(messages.get("queue.announce.current", view.currentGameName()))
            .setDescription(messages.get("queue.announce.proposer", view.currentProposerId()));
    if (view.currentArtUrl() != null && !view.currentArtUrl().isBlank()) {
      embed.setImage(view.currentArtUrl());
    }
    embed.addField("​", upNext(view), false);
    return embed.build();
  }

  private String upNext(AnnouncementView view) {
    if (view.upNext().isEmpty()) {
      return messages.get("queue.announce.upnext")
          + "\n"
          + messages.get("queue.announce.upnext.empty");
    }
    StringBuilder body = new StringBuilder(messages.get("queue.announce.upnext")).append('\n');
    int position = 1;
    for (AnnouncementView.UpNext entry : view.upNext()) {
      body.append(
              messages.get(
                  "queue.announce.upnext.entry", position++, entry.name(), entry.upvoteCount()))
          .append('\n');
    }
    return body.toString();
  }
}
