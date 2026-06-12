package bot.infrastructure.persistence.queue;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link QueueCooldownEntity}: one row per (guild, member). Field names
 * match the entity's {@code @Id} fields, as required by {@code @IdClass}.
 */
public class QueueCooldownId implements Serializable {

  private Long guildId;
  private Long memberId;

  public QueueCooldownId() {
    // for JPA
  }

  public QueueCooldownId(Long guildId, Long memberId) {
    this.guildId = guildId;
    this.memberId = memberId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof QueueCooldownId that)) {
      return false;
    }
    return Objects.equals(guildId, that.guildId) && Objects.equals(memberId, that.memberId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(guildId, memberId);
  }
}
