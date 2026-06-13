package bot.infrastructure.persistence.participation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Maps the mutable per-server configuration row {@code guild_participation_config}. */
@Entity
@Table(name = "guild_participation_config")
public class GuildParticipationConfigEntity {

  @Id
  @Column(name = "guild_id")
  private Long guildId;

  @Column(name = "minutes_per_drop", nullable = false)
  private int minutesPerDrop;

  @Column(name = "coins_per_drop", nullable = false)
  private int coinsPerDrop;

  @Column(name = "free_first_proposal", nullable = false)
  private boolean freeFirstProposal;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected GuildParticipationConfigEntity() {
    // for JPA
  }

  public GuildParticipationConfigEntity(
      Long guildId, int minutesPerDrop, int coinsPerDrop, boolean freeFirstProposal) {
    this.guildId = guildId;
    this.minutesPerDrop = minutesPerDrop;
    this.coinsPerDrop = coinsPerDrop;
    this.freeFirstProposal = freeFirstProposal;
    this.updatedAt = OffsetDateTime.now();
  }

  public Long getGuildId() {
    return guildId;
  }

  public int getMinutesPerDrop() {
    return minutesPerDrop;
  }

  public void setMinutesPerDrop(int minutesPerDrop) {
    this.minutesPerDrop = minutesPerDrop;
  }

  public int getCoinsPerDrop() {
    return coinsPerDrop;
  }

  public void setCoinsPerDrop(int coinsPerDrop) {
    this.coinsPerDrop = coinsPerDrop;
  }

  public boolean isFreeFirstProposal() {
    return freeFirstProposal;
  }

  public void setFreeFirstProposal(boolean freeFirstProposal) {
    this.freeFirstProposal = freeFirstProposal;
  }

  public void touch() {
    this.updatedAt = OffsetDateTime.now();
  }
}
