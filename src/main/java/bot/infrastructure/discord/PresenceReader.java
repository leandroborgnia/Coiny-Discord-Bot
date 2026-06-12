package bot.infrastructure.discord;

import bot.domain.queue.CapturedGame;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Activity.ActivityType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.RichPresence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a member's live Discord Rich Presence on demand and maps the first {@code PLAYING} activity
 * to a {@link CapturedGame} (FR-026). Uses {@code retrieveMembersByIds(true, id)} so presence is
 * fetched targeted and only at propose time — no roster/presence is retained
 * (MemberCachePolicy.NONE in {@link JdaConfig}). The fetch BLOCKS, so it MUST be called off the JDA
 * gateway thread (the caller offloads it) — otherwise it deadlocks the thread that would deliver
 * the member chunk. Returns empty when the member shares no readable game activity, or on any
 * failure.
 *
 * <p>Holds no JDA reference (the {@link Guild} is supplied per call), so it is an unconditional
 * bean — available even when Discord is disabled in tests; {@code capture} is simply never invoked
 * there.
 */
@Component
public class PresenceReader {

  private static final Logger log = LoggerFactory.getLogger(PresenceReader.class);

  /** Capture the member's current game, or empty when none is readable. */
  public Optional<CapturedGame> capture(Guild guild, long memberId) {
    try {
      List<Member> members = guild.retrieveMembersByIds(true, memberId).get();
      if (members.isEmpty()) {
        log.warn(
            "presence capture: no member returned for {} in guild {}", memberId, guild.getId());
        return Optional.empty();
      }
      Member member = members.get(0);
      List<Activity> activities = member.getActivities();
      log.info(
          "presence capture for member {} in guild {}: onlineStatus={}, {} activities {}",
          memberId,
          guild.getId(),
          member.getOnlineStatus(),
          activities.size(),
          activities.stream().map(a -> a.getType() + "/" + a.getName()).toList());
      return activities.stream()
          .filter(activity -> activity.getType() == ActivityType.PLAYING)
          .findFirst()
          .map(PresenceReader::toCapturedGame);
    } catch (RuntimeException e) {
      log.warn("presence capture failed for {} in guild {}", memberId, guild.getId(), e);
      return Optional.empty();
    }
  }

  private static CapturedGame toCapturedGame(Activity activity) {
    RichPresence rp = activity.isRich() ? activity.asRichPresence() : null;
    Long applicationId = rp != null ? rp.getApplicationIdLong() : null;
    String details = rp != null ? rp.getDetails() : null;
    String state = rp != null ? rp.getState() : null;
    return new CapturedGame(applicationId, activity.getName(), details, state, null, null, null);
  }
}
