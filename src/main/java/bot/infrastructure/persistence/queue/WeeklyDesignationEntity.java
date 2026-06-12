package bot.infrastructure.persistence.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Maps an append-only {@code weekly_designation} audit row (FR-022). */
@Entity
@Table(name = "weekly_designation")
public class WeeklyDesignationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "guild_id", nullable = false)
  private Long guildId;

  @Column(name = "week_number", nullable = false)
  private int weekNumber;

  @Column(name = "slot_id")
  private Long slotId;

  @Column(name = "game_identity")
  private String gameIdentity;

  @Column(name = "game_name")
  private String gameName;

  @Column(name = "designated_at", nullable = false)
  private OffsetDateTime designatedAt;

  protected WeeklyDesignationEntity() {
    // for JPA
  }

  public Long getId() {
    return id;
  }

  public Long getGuildId() {
    return guildId;
  }

  public int getWeekNumber() {
    return weekNumber;
  }

  public Long getSlotId() {
    return slotId;
  }

  public String getGameIdentity() {
    return gameIdentity;
  }

  public String getGameName() {
    return gameName;
  }

  public OffsetDateTime getDesignatedAt() {
    return designatedAt;
  }
}
