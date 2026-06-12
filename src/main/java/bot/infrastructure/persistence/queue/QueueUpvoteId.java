package bot.infrastructure.persistence.queue;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link QueueUpvoteEntity}: one row per (slot, member, appearance).
 * Field names match the entity's {@code @Id} fields, as required by {@code @IdClass}.
 */
public class QueueUpvoteId implements Serializable {

  private Long slotId;
  private Long memberId;
  private UUID gameInstanceId;

  public QueueUpvoteId() {
    // for JPA
  }

  public QueueUpvoteId(Long slotId, Long memberId, UUID gameInstanceId) {
    this.slotId = slotId;
    this.memberId = memberId;
    this.gameInstanceId = gameInstanceId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof QueueUpvoteId that)) {
      return false;
    }
    return Objects.equals(slotId, that.slotId)
        && Objects.equals(memberId, that.memberId)
        && Objects.equals(gameInstanceId, that.gameInstanceId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(slotId, memberId, gameInstanceId);
  }
}
