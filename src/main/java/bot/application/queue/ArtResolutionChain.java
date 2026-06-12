package bot.application.queue;

import bot.domain.queue.ArtCachePort;
import bot.domain.queue.ArtEntry;
import bot.domain.queue.ArtResolverPort;
import bot.domain.queue.ArtSource;
import bot.domain.queue.GameIdentity;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves a slot's cover image at render time (FR-027), never blocking or failing the propose
 * path. Order: (1) the captured Rich-Presence asset; (2) the cache (a {@code NONE} hit is a cached
 * miss → name-only, no IGDB); (3) IGDB on a cache miss, storing the result (or a {@code NONE} miss)
 * so IGDB is queried at most once per game identity; (4) any failure degrades to empty (name-only).
 * The whole chain is wrapped so it never throws.
 */
@Component
public class ArtResolutionChain {

  private final ArtCachePort cachePort;
  private final ArtResolverPort resolverPort;

  public ArtResolutionChain(ArtCachePort cachePort, ArtResolverPort resolverPort) {
    this.cachePort = cachePort;
    this.resolverPort = resolverPort;
  }

  /** The cover url to render for this game, or empty to render name-only. Never throws. */
  public Optional<String> coverFor(GameIdentity identity, String richPresenceAsset, String name) {
    if (richPresenceAsset != null && !richPresenceAsset.isBlank()) {
      return Optional.of(richPresenceAsset); // (1) Rich-Presence asset
    }
    try {
      Optional<ArtEntry> cached = cachePort.lookup(identity); // (2) cache
      if (cached.isPresent()) {
        ArtEntry entry = cached.get();
        return entry.isMiss() ? Optional.empty() : Optional.of(entry.imageUrl());
      }
      Optional<String> resolved = resolverPort.resolveCover(identity, name); // (3) IGDB
      if (resolved.isPresent()) {
        cachePort.store(identity, resolved.get(), ArtSource.IGDB);
        return resolved;
      }
      cachePort.store(identity, null, ArtSource.NONE); // cache the miss
      return Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty(); // (4) best-effort: degrade to name-only
    }
  }
}
