package bot.domain.liveness;

/**
 * Domain value object describing the outcome of a liveness check. Pure Java — no Spring, JDA, or
 * persistence types.
 */
public record LivenessStatus(boolean reachable, String detail) {

  public static LivenessStatus up(String detail) {
    return new LivenessStatus(true, detail);
  }

  public static LivenessStatus down(String detail) {
    return new LivenessStatus(false, detail);
  }
}
