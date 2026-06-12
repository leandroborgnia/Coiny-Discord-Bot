package bot.infrastructure.persistence.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Maps the mutable per-guild rotation clock {@code queue_rotation_state}. */
@Entity
@Table(name = "queue_rotation_state")
public class QueueRotationStateEntity {

  @Id
  @Column(name = "guild_id")
  private Long guildId;

  @Column(name = "current_slot_id")
  private Long currentSlotId;

  @Column(name = "current_week_number", nullable = false)
  private int currentWeekNumber;

  @Column(name = "last_pop_at")
  private OffsetDateTime lastPopAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected QueueRotationStateEntity() {
    // for JPA
  }

  public QueueRotationStateEntity(Long guildId) {
    this.guildId = guildId;
    this.currentWeekNumber = 0;
    this.updatedAt = OffsetDateTime.now();
  }

  public Long getGuildId() {
    return guildId;
  }

  public Long getCurrentSlotId() {
    return currentSlotId;
  }

  public void setCurrentSlotId(Long currentSlotId) {
    this.currentSlotId = currentSlotId;
  }

  public int getCurrentWeekNumber() {
    return currentWeekNumber;
  }

  public void setCurrentWeekNumber(int currentWeekNumber) {
    this.currentWeekNumber = currentWeekNumber;
  }

  public OffsetDateTime getLastPopAt() {
    return lastPopAt;
  }

  public void setLastPopAt(OffsetDateTime lastPopAt) {
    this.lastPopAt = lastPopAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void touch() {
    this.updatedAt = OffsetDateTime.now();
  }
}
