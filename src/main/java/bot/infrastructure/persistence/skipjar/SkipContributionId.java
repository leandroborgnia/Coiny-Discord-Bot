package bot.infrastructure.persistence.skipjar;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link SkipContributionEntity}: one row per (guild, run, member) —
 * enforcing once-per-run. Field names match the entity's {@code @Id} fields, as required by
 * {@code @IdClass}.
 */
public class SkipContributionId implements Serializable {

  private Long guildId;
  private Integer weekNumber;
  private Long memberId;

  public SkipContributionId() {
    // for JPA
  }

  public SkipContributionId(Long guildId, Integer weekNumber, Long memberId) {
    this.guildId = guildId;
    this.weekNumber = weekNumber;
    this.memberId = memberId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SkipContributionId that)) {
      return false;
    }
    return Objects.equals(guildId, that.guildId)
        && Objects.equals(weekNumber, that.weekNumber)
        && Objects.equals(memberId, that.memberId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(guildId, weekNumber, memberId);
  }
}
