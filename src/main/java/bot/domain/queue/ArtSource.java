package bot.domain.queue;

/**
 * Where a slot's resolved cover art came from. {@code NONE} marks a cached miss so IGDB is queried
 * at most once per game identity (FR-027).
 */
public enum ArtSource {
  RICH_PRESENCE,
  IGDB,
  NONE
}
