package bot.application.queue;

import bot.domain.queue.AnnouncementView;
import java.util.Optional;

/**
 * Result of a rotation advance for one guild: how many weekly advances were applied (0 = nothing
 * due), and the single announcement to post — present only when a channel is configured and the
 * final designation was non-empty (downtime catch-up announces just the final current game,
 * FR-036).
 */
public record AdvanceResult(int advancesApplied, Optional<AnnouncementView> finalAnnouncement) {}
