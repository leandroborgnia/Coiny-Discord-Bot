package bot.application.queue;

import bot.application.queue.ToggleUpvoteResult.Outcome;
import bot.domain.queue.AnnouncementRef;
import bot.domain.queue.AnnouncementView;
import bot.domain.queue.GuildQueueConfig;
import bot.domain.queue.QueueConfigPort;
import bot.domain.queue.QueuePort;
import bot.domain.queue.UpvotePort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The upvote write path (US5, FR-029/030/031/038). Toggles the member's one-or-zero upvote on a
 * slot's current appearance, idempotent across multiple ephemeral renders (the PK + ON CONFLICT
 * guarantee at most one row per member per appearance). A press carrying a stale {@code
 * gameInstanceId} (the game was replaced) writes nothing. On a real change, when a live
 * announcement message exists, it returns where and what to edit so the handler refreshes that
 * single surface — the ephemeral view is never re-rendered. Upvotes never affect order or rotation.
 */
@Service
public class UpvoteService {

  private final QueuePort queuePort;
  private final UpvotePort upvotePort;
  private final QueueConfigPort configPort;
  private final AnnouncementAssembler announcementAssembler;

  public UpvoteService(
      QueuePort queuePort,
      UpvotePort upvotePort,
      QueueConfigPort configPort,
      AnnouncementAssembler announcementAssembler) {
    this.queuePort = queuePort;
    this.upvotePort = upvotePort;
    this.configPort = configPort;
    this.announcementAssembler = announcementAssembler;
  }

  @Transactional
  public ToggleUpvoteResult toggle(ToggleUpvoteRequest request) {
    Optional<UUID> current = queuePort.currentInstance(request.slotId());
    if (current.isEmpty()) {
      return new ToggleUpvoteResult(Outcome.NO_SLOT, false, 0, Optional.empty(), Optional.empty());
    }
    if (!current.get().equals(request.gameInstanceId())) {
      // Stale button — the slot's game was replaced (FR-030). No write, no announcement edit.
      return new ToggleUpvoteResult(Outcome.STALE, false, 0, Optional.empty(), Optional.empty());
    }

    boolean changed =
        upvotePort.toggle(request.slotId(), request.memberId(), request.gameInstanceId());
    int newCount = upvotePort.count(request.slotId(), request.gameInstanceId());

    if (changed) {
      GuildQueueConfig config = configPort.get(request.guildId());
      if (config.latestAnnouncementChannelId() != null
          && config.latestAnnouncementMessageId() != null) {
        AnnouncementRef ref =
            new AnnouncementRef(
                request.guildId(),
                config.latestAnnouncementChannelId(),
                config.latestAnnouncementMessageId());
        Optional<AnnouncementView> view = announcementAssembler.assemble(request.guildId());
        return new ToggleUpvoteResult(Outcome.TOGGLED, true, newCount, Optional.of(ref), view);
      }
    }
    return new ToggleUpvoteResult(
        Outcome.TOGGLED, changed, newCount, Optional.empty(), Optional.empty());
  }
}
