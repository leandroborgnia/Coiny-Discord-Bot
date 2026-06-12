package bot.domain.queue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the per-guild queue. The application calls these inside one transaction; the
 * implementation owns the Postgres specifics (a per-guild {@code pg_advisory_xact_lock}, partial
 * unique indexes for one-queued-slot-per-member and unique position, {@code ON CONFLICT}
 * idempotency on {@code propose_interaction_id}). Domain/JDK types only (Principle II).
 */
public interface QueuePort {

  /** Serialize concurrent mutations to one guild's queue (transaction-level advisory lock). */
  void lockQueue(long guildId);

  /** The guild's queued slots, ordered by position (the top is position-smallest). */
  List<QueueSlot> queued(long guildId);

  /** The member's own queued slot, if any (at most one — FR-003). */
  Optional<QueueSlot> ownQueued(long guildId, long memberId);

  /** Look up a slot by its propose idempotency (interaction) id, if already applied. */
  Optional<QueueSlot> findByProposeInteraction(long proposeInteractionId);

  /** Look up any slot by id (e.g. the current designated game, which is PLAYED), if it exists. */
  Optional<QueueSlot> findSlot(long slotId);

  /** Append a slot; idempotent on {@code proposeInteractionId}. Returns the stored slot. */
  QueueSlot append(NewSlot slot);

  /**
   * Replace a queued slot's captured game, keeping its position; mints a new {@code gameInstanceId}
   * so the visible upvote count resets and stale upvote buttons no longer apply (FR-034).
   */
  void replaceGame(long slotId, CapturedGame game, GameIdentity identity, UUID newInstanceId);

  /**
   * The slot's live {@code gameInstanceId} (for stale-upvote-button checks), if it still exists.
   */
  Optional<UUID> currentInstance(long slotId);

  /** Remove a still-QUEUED slot (withdraw). */
  void withdraw(long slotId);

  /** The current top (smallest-position, QUEUED) slot, if any. */
  Optional<QueueSlot> top(long guildId);

  /** Mark a slot PLAYED for the given week (clears its position). */
  void markPlayed(long slotId, int week);

  /** Close the position gap after a pop/withdraw so the order stays a dense total order. */
  void shiftUp(long guildId);

  /** N for the cooldown: how many other slots remain QUEUED besides the given one. */
  int otherQueuedCount(long guildId, long excludingSlotId);
}
