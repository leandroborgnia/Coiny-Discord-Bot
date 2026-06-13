package bot.domain.participation;

/**
 * The per-server flat earning rate: {@code minutesPerDrop} qualifying minutes mint one drop of
 * {@code coinsPerDrop} whole coins (FR-002). Both must be positive integers; the out-of-the-box
 * default is one coin per hour ({@code 60/1}).
 *
 * <p>Pure domain type — no framework imports.
 */
public record ParticipationRate(int minutesPerDrop, int coinsPerDrop) {

  public ParticipationRate {
    if (minutesPerDrop < 1) {
      throw new IllegalArgumentException("minutesPerDrop must be >= 1, was " + minutesPerDrop);
    }
    if (coinsPerDrop < 1) {
      throw new IllegalArgumentException("coinsPerDrop must be >= 1, was " + coinsPerDrop);
    }
  }

  /** The default rate applied until a server changes it: one coin per 60 qualifying minutes. */
  public static ParticipationRate defaults() {
    return new ParticipationRate(60, 1);
  }
}
