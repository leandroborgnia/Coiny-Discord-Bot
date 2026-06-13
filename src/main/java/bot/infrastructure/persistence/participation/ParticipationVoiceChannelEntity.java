package bot.infrastructure.persistence.participation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Maps a designated voice channel row {@code participation_voice_channel} (FR-012/013/015). */
@Entity
@Table(name = "participation_voice_channel")
@IdClass(ParticipationVoiceChannelId.class)
public class ParticipationVoiceChannelEntity {

  @Id
  @Column(name = "guild_id")
  private Long guildId;

  @Id
  @Column(name = "channel_id")
  private Long channelId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected ParticipationVoiceChannelEntity() {
    // for JPA
  }

  public ParticipationVoiceChannelEntity(Long guildId, Long channelId) {
    this.guildId = guildId;
    this.channelId = channelId;
    this.createdAt = OffsetDateTime.now();
  }

  public Long getGuildId() {
    return guildId;
  }

  public Long getChannelId() {
    return channelId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
