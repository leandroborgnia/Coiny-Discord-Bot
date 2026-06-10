package bot.infrastructure.persistence.coin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Maps the mutable per-server configuration row {@code guild_coin_config}. */
@Entity
@Table(name = "guild_coin_config")
public class GuildCoinConfigEntity {

  @Id
  @Column(name = "guild_id")
  private Long guildId;

  @Column(name = "moderator_role_id")
  private Long moderatorRoleId;

  @Column(name = "coin_cap", nullable = false)
  private int coinCap;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected GuildCoinConfigEntity() {
    // for JPA
  }

  public GuildCoinConfigEntity(Long guildId, Long moderatorRoleId, int coinCap) {
    this.guildId = guildId;
    this.moderatorRoleId = moderatorRoleId;
    this.coinCap = coinCap;
    this.updatedAt = OffsetDateTime.now();
  }

  public Long getGuildId() {
    return guildId;
  }

  public Long getModeratorRoleId() {
    return moderatorRoleId;
  }

  public void setModeratorRoleId(Long moderatorRoleId) {
    this.moderatorRoleId = moderatorRoleId;
  }

  public int getCoinCap() {
    return coinCap;
  }

  public void setCoinCap(int coinCap) {
    this.coinCap = coinCap;
  }

  public void touch() {
    this.updatedAt = OffsetDateTime.now();
  }
}
