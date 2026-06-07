package bot.domain.liveness;

/**
 * Outbound port: "can we reach and read the data store?". Implemented by infrastructure. The
 * contract never throws for an ordinary "store down" condition — it returns {@link
 * LivenessStatus#down(String)} instead, so callers always receive a value.
 */
public interface LivenessProbePort {

  LivenessStatus probe();
}
