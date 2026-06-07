package bot.application.liveness;

/** Result record for a liveness check. */
public record CheckLivenessResult(boolean reachable, String detail) {}
