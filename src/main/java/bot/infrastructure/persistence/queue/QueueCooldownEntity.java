package bot.infrastructure.persistence.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Maps the mutable {@code queue_cooldown} row ("wait N games", FR-011/012). */
@Entity
@Table(name = "queue_cooldown")
@IdClass(QueueCooldownId.class)
public class QueueCooldownEntity {

  @Id
  @Column(name = "guild_id")
  private Long guildId;

  @Id
  @Column(name = "member_id")
  private Long memberId;

  @Column(name = "games_remaining", nullable = false)
  private int gamesRemaining;

  @Column(name = "set_at", nullable = false)
  private OffsetDateTime setAt;

  protected QueueCooldownEntity() {
    // for JPA
  }

  public QueueCooldownEntity(Long guildId, Long memberId, int gamesRemaining) {
    this.guildId = guildId;
    this.memberId = memberId;
    this.gamesRemaining = gamesRemaining;
    this.setAt = OffsetDateTime.now();
  }

  public Long getGuildId() {
    return guildId;
  }

  public Long getMemberId() {
    return memberId;
  }

  public int getGamesRemaining() {
    return gamesRemaining;
  }

  public void setGamesRemaining(int gamesRemaining) {
    this.gamesRemaining = gamesRemaining;
  }

  public OffsetDateTime getSetAt() {
    return setAt;
  }
}
