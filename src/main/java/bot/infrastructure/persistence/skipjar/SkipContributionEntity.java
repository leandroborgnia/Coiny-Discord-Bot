package bot.infrastructure.persistence.skipjar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Maps the per-run skip-contribution row {@code skip_contribution}. The insert in the adapter is a
 * native statement (so a duplicate PK raises a unique violation that rolls back the debit — the
 * once-per-run backstop, FR-002); this entity backs reads, the count, and Hibernate schema
 * validation.
 */
@Entity
@Table(name = "skip_contribution")
@IdClass(SkipContributionId.class)
public class SkipContributionEntity {

  @Id
  @Column(name = "guild_id")
  private Long guildId;

  @Id
  @Column(name = "week_number")
  private Integer weekNumber;

  @Id
  @Column(name = "member_id")
  private Long memberId;

  @Column(name = "movement_id", nullable = false)
  private long movementId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected SkipContributionEntity() {
    // for JPA
  }

  public Long getGuildId() {
    return guildId;
  }

  public Integer getWeekNumber() {
    return weekNumber;
  }

  public Long getMemberId() {
    return memberId;
  }

  public long getMovementId() {
    return movementId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
