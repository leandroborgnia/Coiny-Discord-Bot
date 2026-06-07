package bot.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps the seeded singleton row of {@code health_check}. Only {@code id} and {@code label} are
 * mapped; the table's informational {@code created_at} column is intentionally not mapped (extra DB
 * columns are fine for {@code ddl-auto: validate}).
 */
@Entity
@Table(name = "health_check")
public class HealthCheckEntity {

  @Id private Short id;

  @Column(nullable = false)
  private String label;

  protected HealthCheckEntity() {
    // for JPA
  }

  public Short getId() {
    return id;
  }

  public String getLabel() {
    return label;
  }
}
