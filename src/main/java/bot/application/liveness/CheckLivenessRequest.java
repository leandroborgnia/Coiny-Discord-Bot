package bot.application.liveness;

/**
 * Request record for {@link LivenessService#check(CheckLivenessRequest)}. Empty for this slice;
 * kept as a record so the service signature is stable as the feature grows.
 */
public record CheckLivenessRequest() {

  private static final CheckLivenessRequest INSTANCE = new CheckLivenessRequest();

  public static CheckLivenessRequest instance() {
    return INSTANCE;
  }
}
