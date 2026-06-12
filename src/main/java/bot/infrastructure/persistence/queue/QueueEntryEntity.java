package bot.infrastructure.persistence.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps a {@code queue_entry} slot (queued or played). {@code snapshot} is the full Rich-Presence
 * capture stored as {@code jsonb} (mapped from a JSON String). Inserts/mutations in the adapter use
 * native queries (advisory lock, {@code ON CONFLICT}); this entity backs reads and Hibernate schema
 * validation.
 */
@Entity
@Table(name = "queue_entry")
public class QueueEntryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "guild_id", nullable = false)
  private Long guildId;

  @Column(name = "proposer_member_id", nullable = false)
  private Long proposerMemberId;

  @Column(nullable = false)
  private String status;

  @Column private Integer position;

  @Column(name = "game_identity", nullable = false)
  private String gameIdentity;

  @Column(name = "game_instance_id", nullable = false)
  private UUID gameInstanceId;

  @Column(name = "game_name", nullable = false)
  private String gameName;

  @Column(name = "application_id")
  private Long applicationId;

  @Column(name = "rp_details")
  private String rpDetails;

  @Column(name = "rp_state")
  private String rpState;

  @Column(name = "rp_large_image")
  private String rpLargeImage;

  @Column(name = "rp_small_image")
  private String rpSmallImage;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String snapshot;

  @Column(name = "coins_spent", nullable = false)
  private int coinsSpent;

  @Column(name = "propose_interaction_id", nullable = false)
  private Long proposeInteractionId;

  @Column(name = "played_week")
  private Integer playedWeek;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected QueueEntryEntity() {
    // for JPA
  }

  public Long getId() {
    return id;
  }

  public Long getGuildId() {
    return guildId;
  }

  public Long getProposerMemberId() {
    return proposerMemberId;
  }

  public String getStatus() {
    return status;
  }

  public Integer getPosition() {
    return position;
  }

  public String getGameIdentity() {
    return gameIdentity;
  }

  public UUID getGameInstanceId() {
    return gameInstanceId;
  }

  public String getGameName() {
    return gameName;
  }

  public Long getApplicationId() {
    return applicationId;
  }

  public String getRpDetails() {
    return rpDetails;
  }

  public String getRpState() {
    return rpState;
  }

  public String getRpLargeImage() {
    return rpLargeImage;
  }

  public String getRpSmallImage() {
    return rpSmallImage;
  }

  public String getSnapshot() {
    return snapshot;
  }

  public int getCoinsSpent() {
    return coinsSpent;
  }

  public Long getProposeInteractionId() {
    return proposeInteractionId;
  }

  public Integer getPlayedWeek() {
    return playedWeek;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
