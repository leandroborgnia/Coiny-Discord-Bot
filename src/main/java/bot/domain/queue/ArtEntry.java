package bot.domain.queue;

/**
 * A cover-art cache row: the resolved {@code imageUrl} (null for a cached miss) and its {@code
 * source}. Keyed externally by {@link GameIdentity}.
 */
public record ArtEntry(String imageUrl, ArtSource source) {

  public boolean isMiss() {
    return source == ArtSource.NONE || imageUrl == null;
  }
}
