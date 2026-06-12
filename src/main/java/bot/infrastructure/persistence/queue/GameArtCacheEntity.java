package bot.infrastructure.persistence.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Maps the {@code game_art_cache} row keyed by game identity ({@code source = NONE} is a miss). */
@Entity
@Table(name = "game_art_cache")
public class GameArtCacheEntity {

  @Id
  @Column(name = "game_identity")
  private String gameIdentity;

  @Column(name = "image_url")
  private String imageUrl;

  @Column(nullable = false)
  private String source;

  @Column(name = "resolved_at", nullable = false)
  private OffsetDateTime resolvedAt;

  protected GameArtCacheEntity() {
    // for JPA
  }

  public GameArtCacheEntity(String gameIdentity, String imageUrl, String source) {
    this.gameIdentity = gameIdentity;
    this.imageUrl = imageUrl;
    this.source = source;
    this.resolvedAt = OffsetDateTime.now();
  }

  public String getGameIdentity() {
    return gameIdentity;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public String getSource() {
    return source;
  }

  public OffsetDateTime getResolvedAt() {
    return resolvedAt;
  }
}
