package bot.domain.queue;

import java.util.List;

/**
 * The render model for a weekly announcement (FR-036): the current game (name, proposer, resolved
 * key-art url) plus an "up next" preview of the next upcoming queued games (small thumbnail, name,
 * current upvote count). Cover-art urls are resolved by the service before constructing this view,
 * so the outbound adapter performs no art lookups.
 */
public record AnnouncementView(
    long guildId,
    String currentGameName,
    long currentProposerId,
    String currentArtUrl,
    List<UpNext> upNext) {

  public AnnouncementView {
    upNext = List.copyOf(upNext);
  }

  /** One "up next" preview row: display name, current upvote count, and an optional thumbnail. */
  public record UpNext(String name, int upvoteCount, String thumbnailUrl) {}
}
