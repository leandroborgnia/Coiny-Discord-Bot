package bot.infrastructure.persistence.coin;

import static org.assertj.core.api.Assertions.assertThat;

import bot.application.coin.AdjustCoinsRequest;
import bot.application.coin.AdjustCoinsResult;
import bot.application.coin.AdjustCoinsService;
import bot.domain.coin.AdjustmentType;
import bot.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies at-most-once application (I4) and race-free overdraw/cap (I5) on real Postgres. */
class CoinIdempotencyConcurrencyTest extends AbstractPostgresIntegrationTest {

  private static final long MOD = 7L;
  private static final int CAP = 100;

  @Autowired private AdjustCoinsService service;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void sameInteractionIdIsAppliedAtMostOnce() {
    long guild = uid();
    long member = uid();
    long interaction = uid();
    configure(guild);
    AdjustCoinsRequest request = grant(guild, member, 50, interaction);

    AdjustCoinsResult first = service.adjust(request);
    AdjustCoinsResult second = service.adjust(request);

    assertThat(first.outcome()).isEqualTo(AdjustCoinsResult.Outcome.APPLIED);
    assertThat(second.outcome()).isEqualTo(AdjustCoinsResult.Outcome.DUPLICATE);
    assertThat(movementCount(guild, member)).isEqualTo(1);
    assertThat(balance(guild, member)).isEqualTo(50);
  }

  @Test
  void concurrentSameInteractionProducesExactlyOneMovement() throws Exception {
    long guild = uid();
    long member = uid();
    long interaction = uid();
    configure(guild);
    AdjustCoinsRequest request = grant(guild, member, 50, interaction);

    runConcurrently(() -> service.adjust(request), () -> service.adjust(request));

    assertThat(movementCount(guild, member)).isEqualTo(1);
    assertThat(balance(guild, member)).isEqualTo(50);
  }

  @Test
  void concurrentGrantsNeverExceedTheCap() throws Exception {
    long guild = uid();
    long member = uid();
    configure(guild);
    // Two distinct grants of 80 each against a cap of 100: the second must forfeit down to the cap.
    AdjustCoinsRequest g1 = grant(guild, member, 80, uid());
    AdjustCoinsRequest g2 = grant(guild, member, 80, uid());

    runConcurrently(() -> service.adjust(g1), () -> service.adjust(g2));

    assertThat(balance(guild, member)).isEqualTo(CAP);
    assertThat(movementCount(guild, member)).isEqualTo(2);
  }

  private void runConcurrently(Callable<?> a, Callable<?> b) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var futures = pool.invokeAll(List.of(wrap(a), wrap(b)));
      for (Future<?> f : futures) {
        f.get(); // surface any exception
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static Callable<Object> wrap(Callable<?> c) {
    return () -> {
      c.call();
      return null;
    };
  }

  private void configure(long guild) {
    // A moderator role must be configured (fail-closed); the caller is admin, and the cap is 100.
    jdbc.update(
        "INSERT INTO guild_coin_config (guild_id, moderator_role_id, coin_cap) VALUES (?,?,?)",
        guild,
        MOD,
        CAP);
  }

  private AdjustCoinsRequest grant(long guild, long member, int amount, long interaction) {
    return new AdjustCoinsRequest(
        guild, MOD, Set.of(), true, member, AdjustmentType.GRANT, amount, "r", interaction);
  }

  private int balance(long guild, long member) {
    Long sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM coin_ledger_entry"
                + " WHERE guild_id = ? AND account = 'MEMBER' AND member_id = ?",
            Long.class,
            guild,
            member);
    return sum == null ? 0 : sum.intValue();
  }

  private int movementCount(long guild, long member) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM coin_movement WHERE guild_id = ? AND member_id = ?",
            Integer.class,
            guild,
            member);
    return count == null ? 0 : count;
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
