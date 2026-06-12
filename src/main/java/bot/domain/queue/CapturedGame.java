package bot.domain.queue;

/**
 * An immutable snapshot of the game captured from a member's Discord Rich Presence at propose time
 * (FR-026). {@code name} is always present (the reliable identity input); everything else is
 * best-effort and may be {@code null} depending on what the launcher exposes. {@code rawJson} holds
 * the full serialized snapshot for future cross-launcher matching (persisted as {@code jsonb}).
 *
 * <p>Pure domain type — no framework imports.
 */
public record CapturedGame(
    Long applicationId,
    String name,
    String details,
    String state,
    String largeImageUrl,
    String smallImageUrl,
    String rawJson) {

  public CapturedGame {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("captured game name must be present");
    }
  }

  /** A minimal capture carrying only the activity name (no Rich-Presence detail available). */
  public static CapturedGame ofName(String name) {
    return new CapturedGame(null, name, null, null, null, null, null);
  }
}
