package bot.domain.queue;

import java.util.Optional;

/**
 * Outbound port for resolving a cover image from an external source (IGDB). Best-effort: returns
 * {@code Optional.empty()} on a miss, any failure, or when disabled (no credentials), so the art
 * chain degrades to name-only and never blocks/fails the propose path (FR-027). Implemented in
 * {@code bot.infrastructure.art}.
 */
public interface ArtResolverPort {

  Optional<String> resolveCover(GameIdentity identity, String name);
}
