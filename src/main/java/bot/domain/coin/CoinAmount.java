package bot.domain.coin;

/**
 * A whole, non-negative coin quantity (the smallest unit is 1). Being integer-typed, fractional
 * amounts are impossible to construct. Use {@link #positive(int)} for amounts that must be at least
 * 1 (adjustments) and {@link #of(int)} for non-negative quantities (balances, caps).
 */
public record CoinAmount(int value) {

  public CoinAmount {
    if (value < 0) {
      throw new NonPositiveAmountException(value);
    }
  }

  /** A non-negative amount (0 allowed). */
  public static CoinAmount of(int value) {
    return new CoinAmount(value);
  }

  /** A strictly positive amount (rejects 0 and negatives) — for adjustment magnitudes. */
  public static CoinAmount positive(int value) {
    if (value <= 0) {
      throw new NonPositiveAmountException(value);
    }
    return new CoinAmount(value);
  }

  public CoinAmount plus(CoinAmount other) {
    return new CoinAmount(this.value + other.value);
  }

  public CoinAmount minus(CoinAmount other) {
    return new CoinAmount(this.value - other.value);
  }

  public CoinAmount min(CoinAmount other) {
    return this.value <= other.value ? this : other;
  }

  public boolean isZero() {
    return value == 0;
  }
}
