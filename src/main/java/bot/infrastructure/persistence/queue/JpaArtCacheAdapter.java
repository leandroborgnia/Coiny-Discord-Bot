package bot.infrastructure.persistence.queue;

import bot.domain.queue.ArtCachePort;
import bot.domain.queue.ArtEntry;
import bot.domain.queue.ArtSource;
import bot.domain.queue.GameIdentity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * JPA adapter for the cover-art cache (keyed by game identity). {@code store} is a native upsert
 * ({@code ON CONFLICT ... DO UPDATE}) so a concurrent resolve never aborts the surrounding read
 * transaction; a stored {@code NONE} marks a cached miss so IGDB is queried at most once per game.
 */
@Component
public class JpaArtCacheAdapter implements ArtCachePort {

  @PersistenceContext private EntityManager entityManager;

  private final GameArtCacheJpaRepository repository;

  public JpaArtCacheAdapter(GameArtCacheJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<ArtEntry> lookup(GameIdentity identity) {
    return repository
        .findById(identity.value())
        .map(e -> new ArtEntry(e.getImageUrl(), ArtSource.valueOf(e.getSource())));
  }

  @Override
  public void store(GameIdentity identity, String imageUrlOrNull, ArtSource source) {
    entityManager
        .createNativeQuery(
            "INSERT INTO game_art_cache (game_identity, image_url, source, resolved_at)"
                + " VALUES (?, ?, ?, now())"
                + " ON CONFLICT (game_identity) DO UPDATE"
                + " SET image_url = EXCLUDED.image_url, source = EXCLUDED.source, resolved_at = now()")
        .setParameter(1, identity.value())
        .setParameter(2, imageUrlOrNull)
        .setParameter(3, source.name())
        .executeUpdate();
  }
}
