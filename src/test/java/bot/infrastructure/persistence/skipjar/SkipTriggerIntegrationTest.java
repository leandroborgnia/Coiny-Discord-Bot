package bot.infrastructure.persistence.skipjar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bot.application.queue.AdvanceRotationService;
import bot.application.skipjar.ContributeRequest;
import bot.application.skipjar.ContributeResult;
import bot.application.skipjar.ContributeToSkipJarService;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPolicy;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.domain.skipjar.JarClosedException;
import bot.domain.skipjar.SkipJarConfigPort;
import bot.infrastructure.persistence.coin.JpaCoinLedgerAdapter;
import bot.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the skip trigger against real Postgres: a threshold-meeting contribution retires the
 * current game and advances exactly one step; one short does nothing; the new run's jar is empty; a
 * normal weekly advance also resets the jar; the dwell reset refuses a follow-on contribution; and
 * SKIP_POT retains the coins (quickstart #9–#13). Gate off and a low floor keep the arithmetic
 * simple.
 */
class SkipTriggerIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final int WEEK = 5;

  @Autowired private ContributeToSkipJarService service;
  @Autowired private AdvanceRotationService advanceService;
  @Autowired private SkipJarConfigPort configPort;
  @Autowired private JpaCoinLedgerAdapter coinAdapter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void thresholdMeetingContributionRetiresTheGameAndAdvancesOneStep() {
    long guild = uid();
    long m1 = uid();
    long m2 = uid();
    configPort.setGate(guild, false);
    configPort.setFloor(guild, 2);
    long currentSlot = setupRun(guild, daysAgo(2));
    seedBalance(guild, m1, 5);
    seedBalance(guild, m2, 5);

    ContributeResult first = service.contribute(req(guild, m1));
    assertThat(first.skipped()).isFalse();

    ContributeResult second = service.contribute(req(guild, m2));

    assertThat(second.skipped()).isTrue();
    assertThat(second.gameName()).isEqualTo("Current Game"); // retired
    assertThat(second.newGameName()).isEqualTo("Next Game"); // now current
    assertThat(currentSlot(guild)).isNotEqualTo(currentSlot); // advanced one step
    assertThat(currentWeek(guild)).isEqualTo(WEEK + 1);
    assertThat(skipPotBalance(guild)).isEqualTo(2); // non-refundable, both coins retained (#13)
  }

  @Test
  void oneShortOfThresholdAdvancesNothing() {
    long guild = uid();
    long m1 = uid();
    configPort.setGate(guild, false);
    configPort.setFloor(guild, 3);
    long currentSlot = setupRun(guild, daysAgo(2));
    seedBalance(guild, m1, 5);

    ContributeResult result = service.contribute(req(guild, m1));

    assertThat(result.skipped()).isFalse();
    assertThat(currentSlot(guild)).isEqualTo(currentSlot); // unchanged
    assertThat(currentWeek(guild)).isEqualTo(WEEK);
    assertThat(jarCount(guild, WEEK)).isEqualTo(1); // accumulates
  }

  @Test
  void afterASkipTheNewRunsJarIsEmptyAndRetiredContributionsDoNotCount() {
    long guild = uid();
    long m1 = uid();
    long m2 = uid();
    configPort.setGate(guild, false);
    configPort.setFloor(guild, 2);
    setupRun(guild, daysAgo(2));
    seedBalance(guild, m1, 5);
    seedBalance(guild, m2, 5);

    service.contribute(req(guild, m1));
    service.contribute(req(guild, m2)); // triggers the skip

    assertThat(jarCount(guild, WEEK + 1)).isZero(); // new run starts empty (#11)
    assertThat(jarCount(guild, WEEK)).isEqualTo(2); // retired rows remain but no longer count
  }

  @Test
  void aNormalWeeklyAdvanceBeforeASkipAlsoResetsTheJar() {
    long guild = uid();
    long m1 = uid();
    configPort.setGate(guild, false);
    configPort.setFloor(guild, 10); // never triggers with one contributor
    setupRun(guild, daysAgo(8)); // dwell elapsed AND a weekly advance is due
    seedBalance(guild, m1, 5);

    service.contribute(req(guild, m1));
    assertThat(jarCount(guild, WEEK)).isEqualTo(1);

    advanceService.advanceDue(guild, Instant.now()); // normal weekly advance rolls the run

    assertThat(currentWeek(guild)).isEqualTo(WEEK + 1);
    assertThat(jarCount(guild, WEEK + 1)).isZero(); // new run empty (#11b)
    assertThat(jarCount(guild, WEEK)).isEqualTo(1); // prior-run rows don't count
  }

  @Test
  void afterASkipTheDwellResetRefusesAFollowOnContribution() {
    long guild = uid();
    long m1 = uid();
    long m2 = uid();
    long m3 = uid();
    configPort.setGate(guild, false);
    configPort.setFloor(guild, 2);
    setupRun(guild, daysAgo(2));
    seedBalance(guild, m1, 5);
    seedBalance(guild, m2, 5);
    seedBalance(guild, m3, 5);

    service.contribute(req(guild, m1));
    service.contribute(req(guild, m2)); // triggers the skip; new game became current just now

    // The new run's dwell has not elapsed, so a follow-on contribution is refused — only one pop.
    assertThatThrownBy(() -> service.contribute(req(guild, m3)))
        .isInstanceOf(JarClosedException.class);
    assertThat(currentWeek(guild)).isEqualTo(WEEK + 1); // exactly one advance
  }

  // --- setup helpers ---

  private static ContributeRequest req(long guild, long member) {
    return new ContributeRequest(guild, member, uid(), Instant.now());
  }

  /** Inserts a current (PLAYED) game plus one queued "Next Game", and the rotation clock. */
  private long setupRun(long guild, Instant lastPop) {
    long current =
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
        "INSERT INTO queue_entry (guild_id, proposer_member_id, status, position,"
            + " game_identity, game_name, coins_spent, propose_interaction_id)"
            + " VALUES (?, ?, 'QUEUED', 1, ?, ?, 1, ?)",
        guild,
        uid(),
        "name:next" + guild,
        "Next Game",
        uid());
    jdbc.update(
        "INSERT INTO queue_rotation_state (guild_id, current_slot_id, current_week_number,"
            + " last_pop_at) VALUES (?, ?, ?, ?)",
        guild,
        current,
        WEEK,
        OffsetDateTime.ofInstant(lastPop, ZoneOffset.UTC));
    return current;
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

  // --- queries ---

  private int jarCount(long guild, int week) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM skip_contribution WHERE guild_id = ? AND week_number = ?",
            Integer.class,
            guild,
            week);
    return n == null ? 0 : n;
  }

  private Long currentSlot(long guild) {
    return jdbc.queryForObject(
        "SELECT current_slot_id FROM queue_rotation_state WHERE guild_id = ?", Long.class, guild);
  }

  private int currentWeek(long guild) {
    Integer w =
        jdbc.queryForObject(
            "SELECT current_week_number FROM queue_rotation_state WHERE guild_id = ?",
            Integer.class,
            guild);
    return w == null ? 0 : w;
  }

  private int skipPotBalance(long guild) {
    Long sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM coin_ledger_entry"
                + " WHERE guild_id = ? AND account = 'SKIP_POT'",
            Long.class,
            guild);
    return sum == null ? 0 : sum.intValue();
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
