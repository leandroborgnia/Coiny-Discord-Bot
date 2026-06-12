package bot.infrastructure.persistence.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps a {@code queue_upvote} row — mutable social state (NOT the coin ledger). The composite key
 * (slot, member, appearance) makes the toggle idempotent and scopes the count to the current
 * appearance (FR-030/031). Toggles in the adapter use native insert {@code ON CONFLICT}/delete.
 */
@Entity
@Table(name = "queue_upvote")
@IdClass(QueueUpvoteId.class)
public class QueueUpvoteEntity {

  @Id
  @Column(name = "slot_id")
  private Long slotId;

  @Id
  @Column(name = "member_id")
  private Long memberId;

  @Id
  @Column(name = "game_instance_id")
  private UUID gameInstanceId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected QueueUpvoteEntity() {
    // for JPA
  }

  public Long getSlotId() {
    return slotId;
  }

  public Long getMemberId() {
    return memberId;
  }

  public UUID getGameInstanceId() {
    return gameInstanceId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
