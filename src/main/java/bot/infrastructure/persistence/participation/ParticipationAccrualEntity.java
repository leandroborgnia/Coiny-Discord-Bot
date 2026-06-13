package bot.infrastructure.persistence.participation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Maps the mutable per-member accrual row {@code participation_accrual}. The upsert in the adapter
 * uses a native {@code ON CONFLICT} query (under the per-account advisory lock); this entity backs
 * reads and Hibernate schema validation.
 */
@Entity
@Table(name = "participation_accrual")
@IdClass(ParticipationAccrualId.class)
public class ParticipationAccrualEntity {

  @Id
  @Column(name = "guild_id")
  private Long guildId;

  @Id
  @Column(name = "member_id")
  private Long memberId;

  @Column(name = "banked_seconds", nullable = false)
  private long bankedSeconds;

  @Column(name = "last_sampled_at")
  private OffsetDateTime lastSampledAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected ParticipationAccrualEntity() {
    // for JPA
  }

  public Long getGuildId() {
    return guildId;
  }

  public Long getMemberId() {
    return memberId;
  }

  public long getBankedSeconds() {
    return bankedSeconds;
  }

  public OffsetDateTime getLastSampledAt() {
    return lastSampledAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
