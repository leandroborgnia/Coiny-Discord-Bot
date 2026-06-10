package bot.infrastructure.persistence.coin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoinLedgerEntryJpaRepository extends JpaRepository<CoinLedgerEntryEntity, Long> {

  /** The member's derived balance: the signed sum of their MEMBER entries (0 if none). */
  @Query(
      "select coalesce(sum(e.amount), 0) from CoinLedgerEntryEntity e "
          + "where e.guildId = :guildId and e.account = 'MEMBER' and e.memberId = :memberId")
  long sumMemberBalance(@Param("guildId") long guildId, @Param("memberId") long memberId);
}
