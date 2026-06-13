package bot.infrastructure.persistence.skipjar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Maps the mutable per-server configuration row {@code guild_skip_jar_config}. */
@Entity
@Table(name = "guild_skip_jar_config")
public class GuildSkipJarConfigEntity {

  @Id
  @Column(name = "guild_id")
  private Long guildId;

  @Column(name = "threshold_floor", nullable = false)
  private int thresholdFloor;

  @Column(name = "dwell_seconds", nullable = false)
  private long dwellSeconds;

  @Column(name = "participation_gate", nullable = false)
  private boolean participationGate;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected GuildSkipJarConfigEntity() {
    // for JPA
  }

  public GuildSkipJarConfigEntity(
      Long guildId, int thresholdFloor, long dwellSeconds, boolean participationGate) {
    this.guildId = guildId;
    this.thresholdFloor = thresholdFloor;
    this.dwellSeconds = dwellSeconds;
    this.participationGate = participationGate;
    this.updatedAt = OffsetDateTime.now();
  }

  public Long getGuildId() {
    return guildId;
  }

  public int getThresholdFloor() {
    return thresholdFloor;
  }

  public void setThresholdFloor(int thresholdFloor) {
    this.thresholdFloor = thresholdFloor;
  }

  public long getDwellSeconds() {
    return dwellSeconds;
  }

  public void setDwellSeconds(long dwellSeconds) {
    this.dwellSeconds = dwellSeconds;
  }

  public boolean isParticipationGate() {
    return participationGate;
  }

  public void setParticipationGate(boolean participationGate) {
    this.participationGate = participationGate;
  }

  public void touch() {
    this.updatedAt = OffsetDateTime.now();
  }
}
