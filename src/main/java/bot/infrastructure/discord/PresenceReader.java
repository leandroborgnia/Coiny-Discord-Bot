package bot.infrastructure.discord;

import bot.domain.queue.CapturedGame;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Activity.ActivityType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.RichPresence;
import org.springframework.stereotype.Component;

/**
 * Reads a member's live Discord Rich Presence on demand and maps the first {@code PLAYING} activity
 * to a {@link CapturedGame} (FR-026). Uses {@code retrieveMembersByIds(true, id)} so presence is
 * fetched targeted and only at propose time — no roster/presence is retained
 * (MemberCachePolicy.NONE in {@link JdaConfig}). The fetch blocks briefly and MUST be called only
 * after the handler has deferred the interaction (Principle V). Returns empty when the member
 * shares no readable game activity, or on any fetch failure.
 *
 * <p>Holds no JDA reference (the {@link Guild} is supplied per call), so it is an unconditional
 * bean — available even when Discord is disabled in tests; {@code capture} is simply never invoked
 * there.
 */
@Component
public class PresenceReader {

  /** Capture the member's current game, or empty when none is readable. */
  public Optional<CapturedGame> capture(Guild guild, long memberId) {
    try {
      List<Member> members = guild.retrieveMembersByIds(true, memberId).get();
      if (members.isEmpty()) {
        return Optional.empty();
      }
      return members.get(0).getActivities().stream()
          .filter(a -> a.getType() == ActivityType.PLAYING)
          .findFirst()
          .map(PresenceReader::toCapturedGame);
    } catch (RuntimeException e) {
      // Presence unavailable (sharing off, member gone, gateway hiccup) — treat as no activity.
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
