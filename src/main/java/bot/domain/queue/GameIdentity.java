package bot.domain.queue;

/**
 * The "which game" key used for the cover-art cache and cooldown/dedup. Stable when an application
 * id is present ({@code app:<id>}); otherwise a best-effort normalized name ({@code name:<norm>}),
 * since Rich-Presence names vary by launcher. Distinct from a slot's per-appearance {@code
 * gameInstanceId} (which binds upvotes).
 *
 * <p>Pure domain type — no framework imports.
 */
public record GameIdentity(String value) {

  public GameIdentity {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("game identity value must be present");
    }
  }

  /** Application id when present, else the normalized activity name. */
  public static GameIdentity of(CapturedGame game) {
    if (game.applicationId() != null) {
      return new GameIdentity("app:" + game.applicationId());
    }
    return new GameIdentity("name:" + normalize(game.name()));
  }

  /**
   * Best-effort name normalization: lowercase, trim, collapse internal whitespace, and strip a
   * trailing launcher tag (e.g. a parenthesized/bracketed "(Steam)"). Cross-launcher matching is
   * not guaranteed (FR-026); this only improves the odds of two slots sharing an art-cache key.
   */
  public static String normalize(String name) {
    String n = name.toLowerCase().strip().replaceAll("\\s+", " ");
    n = n.replaceAll("\\s*[\\(\\[][^\\(\\)\\[\\]]*[\\)\\]]\\s*$", "").strip();
    return n.isBlank() ? name.toLowerCase().strip() : n;
  }
}
