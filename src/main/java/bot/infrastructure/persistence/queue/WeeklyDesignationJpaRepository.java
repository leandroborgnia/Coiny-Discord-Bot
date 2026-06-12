package bot.infrastructure.persistence.queue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WeeklyDesignationJpaRepository
    extends JpaRepository<WeeklyDesignationEntity, Long> {}
