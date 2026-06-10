package bot.infrastructure.persistence.coin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoinMovementJpaRepository extends JpaRepository<CoinMovementEntity, Long> {

  Optional<CoinMovementEntity> findByInteractionId(long interactionId);

  List<CoinMovementEntity> findByGuildIdAndMemberIdOrderByIdDesc(
      long guildId, long memberId, Limit limit);
}
