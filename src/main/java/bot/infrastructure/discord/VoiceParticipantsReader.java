package bot.infrastructure.discord;

import bot.domain.queue.GameIdentity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Reads, from JDA's in-memory cache (no REST), the members currently connected to a guild's
 * designated voice channels who are playing the current game. A member qualifies iff the {@link
 * GameIdentity} of their first {@code PLAYING} activity (via the shared {@link GameActivities}
 * mapper, so it matches propose-time capture) equals {@code currentGame}. Returns empty on any miss
 * — guild/channel uncached or activity unreadable simply means "not qualifying" (the "activity
 * hidden" edge). Gated by {@code discord.enabled}; absent in tests (no JDA).
 */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class VoiceParticipantsReader {

  private final JDA jda;

  // @Lazy mirrors JdaAnnouncementAdapter: this reader is only invoked by the sweep (well after
  // startup), so the proxy resolves to the real JDA on first use and never participates in a cycle.
  public VoiceParticipantsReader(@Lazy JDA jda) {
    this.jda = jda;
  }

  public Set<Long> qualifyingMembers(
      long guildId, List<Long> channelIds, GameIdentity currentGame) {
    Guild guild = jda.getGuildById(guildId);
    if (guild == null) {
      return Set.of();
    }
    Set<Long> qualifying = new HashSet<>();
    for (long channelId : channelIds) {
      VoiceChannel channel = guild.getVoiceChannelById(channelId);
      if (channel == null) {
        continue;
      }
      for (Member member : channel.getMembers()) {
        GameActivities.firstPlaying(member.getActivities())
            .map(GameIdentity::of)
            .filter(currentGame::equals)
            .ifPresent(game -> qualifying.add(member.getIdLong()));
      }
    }
    return qualifying;
  }
}
