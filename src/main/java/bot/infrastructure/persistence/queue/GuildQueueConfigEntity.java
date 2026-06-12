package bot.infrastructure.persistence.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Maps the mutable per-server queue configuration row {@code guild_queue_config}. */
@Entity
@Table(name = "guild_queue_config")
public class GuildQueueConfigEntity {

  @Id
  @Column(name = "guild_id")
  private Long guildId;

  @Column(name = "propose_cost", nullable = false)
  private int proposeCost;

  @Column(name = "bump_cost", nullable = false)
  private int bumpCost;

  @Column(name = "announcement_channel_id")
  private Long announcementChannelId;

  @Column(name = "latest_announcement_channel_id")
  private Long latestAnnouncementChannelId;

  @Column(name = "latest_announcement_message_id")
  private Long latestAnnouncementMessageId;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected GuildQueueConfigEntity() {
    // for JPA
  }

  public GuildQueueConfigEntity(Long guildId, int proposeCost, int bumpCost) {
    this.guildId = guildId;
    this.proposeCost = proposeCost;
    this.bumpCost = bumpCost;
    this.updatedAt = OffsetDateTime.now();
  }

  public Long getGuildId() {
    return guildId;
  }

  public int getProposeCost() {
    return proposeCost;
  }

  public void setProposeCost(int proposeCost) {
    this.proposeCost = proposeCost;
  }

  public int getBumpCost() {
    return bumpCost;
  }

  public void setBumpCost(int bumpCost) {
    this.bumpCost = bumpCost;
  }

  public Long getAnnouncementChannelId() {
    return announcementChannelId;
  }

  public void setAnnouncementChannelId(Long announcementChannelId) {
    this.announcementChannelId = announcementChannelId;
  }

  public Long getLatestAnnouncementChannelId() {
    return latestAnnouncementChannelId;
  }

  public void setLatestAnnouncementChannelId(Long latestAnnouncementChannelId) {
    this.latestAnnouncementChannelId = latestAnnouncementChannelId;
  }

  public Long getLatestAnnouncementMessageId() {
    return latestAnnouncementMessageId;
  }

  public void setLatestAnnouncementMessageId(Long latestAnnouncementMessageId) {
    this.latestAnnouncementMessageId = latestAnnouncementMessageId;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void touch() {
    this.updatedAt = OffsetDateTime.now();
  }
}
