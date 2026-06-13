package bot.infrastructure.discord;

import bot.domain.queue.CapturedGame;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Activity.ActivityType;
import net.dv8tion.jda.api.entities.RichPresence;

/**
 * Shared pure mapping from a JDA {@link Activity} to a {@link CapturedGame}. Used by BOTH {@link
 * PresenceReader} (propose-time capture) and {@code VoiceParticipantsReader} (sweep matching) so
 * the "which game" both paths see is identical byte-for-byte (FR-026). The first {@code PLAYING}
 * activity wins; non-playing activities (listening, streaming, custom status) are ignored.
 */
public final class GameActivities {

  private GameActivities() {}

  /**
   * The first {@code PLAYING} activity as a {@link CapturedGame}, or empty when none is present.
   */
  public static Optional<CapturedGame> firstPlaying(List<Activity> activities) {
    return activities.stream()
        .filter(activity -> activity.getType() == ActivityType.PLAYING)
        .findFirst()
        .map(GameActivities::toCapturedGame);
  }

  /** Map a single activity to a {@link CapturedGame}, pulling Rich-Presence detail when present. */
  public static CapturedGame toCapturedGame(Activity activity) {
    RichPresence rp = activity.isRich() ? activity.asRichPresence() : null;
    Long applicationId = rp != null ? rp.getApplicationIdLong() : null;
    String details = rp != null ? rp.getDetails() : null;
    String state = rp != null ? rp.getState() : null;
    return new CapturedGame(applicationId, activity.getName(), details, state, null, null, null);
  }
}
