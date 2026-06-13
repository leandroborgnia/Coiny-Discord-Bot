package bot.domain.participation;

/**
 * The result of splitting banked qualifying seconds: {@code drops} whole drops ready to mint and
 * the {@code remainderSeconds} carried toward the next drop.
 *
 * <p>Pure domain type — no framework imports.
 */
public record DropsAndRemainder(int drops, long remainderSeconds) {}
