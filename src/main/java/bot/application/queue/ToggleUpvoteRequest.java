package bot.application.queue;

import java.util.UUID;

/**
 * Request to toggle the member's upvote on a queued slot. {@code gameInstanceId} is the appearance
 * the button was rendered for (encoded in its component id) — used to detect a stale button after
 * the game was replaced (FR-030).
 */
public record ToggleUpvoteRequest(
    long guildId, long memberId, long slotId, UUID gameInstanceId, long interactionId) {}
