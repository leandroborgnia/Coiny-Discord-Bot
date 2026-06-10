package bot.infrastructure.persistence.coin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Maps an append-only {@code coin_ledger_entry} posting (one signed line of a balanced movement).
 */
@Entity
@Table(name = "coin_ledger_entry")
public class CoinLedgerEntryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "movement_id", nullable = false)
  private Long movementId;

  @Column(name = "guild_id", nullable = false)
  private Long guildId;

  @Column(nullable = false)
  private String account;

  @Column(name = "member_id")
  private Long memberId;

  @Column(nullable = false)
  private long amount;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected CoinLedgerEntryEntity() {
    // for JPA
  }

  public CoinLedgerEntryEntity(
      Long movementId, Long guildId, String account, Long memberId, long amount) {
    this.movementId = movementId;
    this.guildId = guildId;
    this.account = account;
    this.memberId = memberId;
    this.amount = amount;
    this.createdAt = OffsetDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public Long getMovementId() {
    return movementId;
  }

  public Long getGuildId() {
    return guildId;
  }

  public String getAccount() {
    return account;
  }

  public Long getMemberId() {
    return memberId;
  }

  public long getAmount() {
    return amount;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
