package bot.infrastructure.persistence.coin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Maps an append-only {@code coin_movement} row. Inserts go through a native {@code ON CONFLICT}
 * query in the adapter (for idempotency); this entity is used for reads (history, idempotency
 * lookup) and Hibernate schema validation.
 */
@Entity
@Table(name = "coin_movement")
public class CoinMovementEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "guild_id", nullable = false)
  private Long guildId;

  @Column(name = "member_id", nullable = false)
  private Long memberId;

  @Column(name = "moderator_id", nullable = false)
  private Long moderatorId;

  @Column(nullable = false)
  private String type;

  @Column(name = "requested_amount", nullable = false)
  private int requestedAmount;

  @Column(name = "credited_amount", nullable = false)
  private int creditedAmount;

  @Column(name = "forfeited_amount", nullable = false)
  private int forfeitedAmount;

  @Column private String reason;

  @Column(name = "interaction_id", nullable = false)
  private Long interactionId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected CoinMovementEntity() {
    // for JPA
  }

  public Long getId() {
    return id;
  }

  public Long getGuildId() {
    return guildId;
  }

  public Long getMemberId() {
    return memberId;
  }

  public Long getModeratorId() {
    return moderatorId;
  }

  public String getType() {
    return type;
  }

  public int getRequestedAmount() {
    return requestedAmount;
  }

  public int getCreditedAmount() {
    return creditedAmount;
  }

  public int getForfeitedAmount() {
    return forfeitedAmount;
  }

  public String getReason() {
    return reason;
  }

  public Long getInteractionId() {
    return interactionId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
