package bot.infrastructure.persistence.queue;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueEntryJpaRepository extends JpaRepository<QueueEntryEntity, Long> {

  List<QueueEntryEntity> findByGuildIdAndStatusOrderByPositionAsc(long guildId, String status);

  Optional<QueueEntryEntity> findByGuildIdAndProposerMemberIdAndStatus(
      long guildId, long proposerMemberId, String status);

  Optional<QueueEntryEntity> findByProposeInteractionId(long proposeInteractionId);

  Optional<QueueEntryEntity> findFirstByGuildIdAndStatusOrderByPositionAsc(
      long guildId, String status);
}
