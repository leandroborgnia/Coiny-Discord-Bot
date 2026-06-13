package bot.infrastructure.persistence.participation;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link ParticipationVoiceChannelEntity}: one row per (guild, channel).
 * Field names match the entity's {@code @Id} fields, as required by {@code @IdClass}.
 */
public class ParticipationVoiceChannelId implements Serializable {

  private Long guildId;
  private Long channelId;

  public ParticipationVoiceChannelId() {
    // for JPA
  }

  public ParticipationVoiceChannelId(Long guildId, Long channelId) {
    this.guildId = guildId;
    this.channelId = channelId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ParticipationVoiceChannelId that)) {
      return false;
    }
    return Objects.equals(guildId, that.guildId) && Objects.equals(channelId, that.channelId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(guildId, channelId);
  }
}
