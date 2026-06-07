package bot.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthCheckJpaRepository extends JpaRepository<HealthCheckEntity, Short> {}
