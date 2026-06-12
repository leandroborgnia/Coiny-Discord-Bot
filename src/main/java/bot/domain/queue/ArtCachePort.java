package bot.domain.queue;

import java.util.Optional;

/**
 * Outbound port for the cover-art cache, keyed by {@link GameIdentity}. A stored {@code NONE} entry
 * records a cached miss so IGDB is queried at most once per game (FR-027).
 */
public interface ArtCachePort {

  Optional<ArtEntry> lookup(GameIdentity identity);

  /** Store a resolved url (or {@code null} with source {@code NONE} to cache a miss). */
  void store(GameIdentity identity, String imageUrlOrNull, ArtSource source);
}
