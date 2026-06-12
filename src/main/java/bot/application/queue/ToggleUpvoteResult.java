package bot.application.queue;

import bot.domain.queue.AnnouncementRef;
import bot.domain.queue.AnnouncementView;
import java.util.Optional;

/**
 * Outcome of an upvote toggle. {@code TOGGLED} (the press was for the live appearance); {@code
 * STALE} (the slot's game was replaced — the button referenced a prior appearance, nothing
 * written); {@code NO_SLOT} (the slot is gone/played). {@code changed} is whether the member's
 * state actually transitioned (false = a no-op duplicate). When it changed and a live announcement
 * surface exists, {@code liveSurface} + {@code announcementView} carry where and what to edit
 * (FR-038); the ephemeral view is never re-rendered (FR-029).
 */
public record ToggleUpvoteResult(
    Outcome outcome,
    boolean changed,
    int newCount,
    Optional<AnnouncementRef> liveSurface,
    Optional<AnnouncementView> announcementView) {

  public enum Outcome {
    TOGGLED,
    STALE,
    NO_SLOT
  }
}
