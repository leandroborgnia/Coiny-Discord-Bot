package bot.application.skipjar;

import static org.assertj.core.api.Assertions.assertThat;

import bot.application.skipjar.SkipJarStatus.State;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPolicy;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.domain.skipjar.SkipJarConfigPort;
import bot.infrastructure.persistence.coin.JpaCoinLedgerAdapter;
import bot.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the skip-jar status read against real Postgres: OPEN reports count/threshold/remaining/
 * earnerCount/floor; NOT_OPEN reports {@code opensAt = becameCurrent + dwell}; NO_GAME never throws
 * (quickstart #14, FR-014).
 */
class ViewSkipJarServiceTest extends AbstractPostgresIntegrationTest {

  private static final int WEEK = 5;

  @Autowired private ViewSkipJarService viewService;
  @Autowired private ContributeToSkipJarService contributeService;
  @Autowired private SkipJarConfigPort configPort;
  @Autowired private JpaCoinLedgerAdapter coinAdapter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void openReportsCountThresholdAndRemaining() {
    long guild = uid();
    long member = uid();
    configPort.setGate(guild, false);
    configPort.setFloor(guild, 5);
    setupCurrentGame(guild, daysAgo(2)); // dwell elapsed
    seedBalance(guild, member, 5);
    contributeService.contribute(new ContributeRequest(guild, member, uid(), Instant.now()));

    SkipJarStatus status = viewService.view(new ViewRequest(guild, Instant.now()));

    assertThat(status.state()).isEqualTo(State.OPEN);
    assertThat(status.gameName()).isEqualTo("Current Game");
    assertThat(status.count()).isEqualTo(1);
    assertThat(status.floor()).isEqualTo(5);
    assertThat(status.earnerCount()).isZero(); // GRANT-seeded, no PARTICIPATION earnings
    assertThat(status.threshold()).isEqualTo(5); // max(majority(0)=1, floor 5)
    assertThat(status.remaining()).isEqualTo(4);
  }

  @Test
  void notOpenReportsWhenTheJarOpens() {
    long guild = uid();
    Instant lastPop = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    setupCurrentGame(guild, lastPop); // became current just now → within 24h dwell

    SkipJarStatus status = viewService.view(new ViewRequest(guild, Instant.now()));

    assertThat(status.state()).isEqualTo(State.NOT_OPEN);
    assertThat(status.gameName()).isEqualTo("Current Game");
    assertThat(status.opensAt()).isEqualTo(lastPop.plus(Duration.ofHours(24)));
  }

  @Test
  void noGameNeverThrows() {
    long guild = uid(); // no rotation state at all

    SkipJarStatus status = viewService.view(new ViewRequest(guild, Instant.now()));

    assertThat(status.state()).isEqualTo(State.NO_GAME);
    assertThat(status.gameName()).isNull();
  }

  // --- helpers ---

  private void setupCurrentGame(long guild, Instant lastPop) {
    long slot =
        jdbc.queryForObject(
            "INSERT INTO queue_entry (guild_id, proposer_member_id, status, position,"
                + " game_identity, game_name, coins_spent, propose_interaction_id, played_week)"
                + " VALUES (?, ?, 'PLAYED', NULL, ?, ?, 1, ?, ?) RETURNING id",
            Long.class,
            guild,
            uid(),
            "name:current" + guild,
            "Current Game",
            uid(),
            WEEK);
    jdbc.update(
        "INSERT INTO queue_rotation_state (guild_id, current_slot_id, current_week_number,"
            + " last_pop_at) VALUES (?, ?, ?, ?)",
        guild,
        slot,
        WEEK,
        OffsetDateTime.ofInstant(lastPop, ZoneOffset.UTC));
  }

  private void seedBalance(long guild, long member, int amount) {
    new TransactionTemplate(txManager)
        .executeWithoutResult(
            status -> {
              PostingPlan plan = CoinLedgerPolicy.planGrant(guild, member, 0, amount, 1000);
              NewMovement movement =
                  new NewMovement(
                      guild,
                      member,
                      member,
                      AdjustmentType.GRANT,
                      plan.requested(),
                      plan.credited(),
                      plan.forfeited(),
                      "seed",
                      uid());
              coinAdapter.append(movement, plan);
            });
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
