package bot.infrastructure.persistence.skipjar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bot.application.skipjar.ContributeRequest;
import bot.application.skipjar.ContributeResult;
import bot.application.skipjar.ContributeToSkipJarService;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPolicy;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.NewMovement;
import bot.domain.coin.OverdrawException;
import bot.domain.coin.PostingPlan;
import bot.domain.skipjar.AlreadyContributedException;
import bot.domain.skipjar.JarClosedException;
import bot.domain.skipjar.NotEligibleToContributeException;
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
 * Exercises a skip-jar contribution against real Postgres: a balanced non-refundable SKIP_JAR
 * debit, the once-per-run PK guard, dwell/earner/balance gating, and the gate toggle (quickstart
 * #3–#8, #13). The threshold floor (3) keeps a single contribution from triggering a skip here.
 */
class SkipContributionIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final int WEEK = 5;

  @Autowired private ContributeToSkipJarService service;
  @Autowired private CoinLedgerPort ledgerPort;
  @Autowired private JpaCoinLedgerAdapter coinAdapter;
  @Autowired private SkipJarConfigPort configPort;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void contributionDebitsOneCoinPostsASkipJarMovementAndCountsTheJar() {
    long guild = uid();
    long member = uid();
    configPort.setGate(guild, false);
    seedBalance(guild, member, 5);
    setupCurrentGame(guild, daysAgo(2)); // dwell (24h) elapsed

    ContributeResult result = service.contribute(req(guild, member));

    assertThat(result.charged()).isTrue();
    assertThat(result.skipped()).isFalse();
    assertThat(result.count()).isEqualTo(1);
    assertThat(ledgerPort.currentBalance(guild, member)).isEqualTo(4); // exactly one coin
    assertThat(skipJarMovementCount(guild, member)).isEqualTo(1);
    assertThat(jarCount(guild, WEEK)).isEqualTo(1);
    assertThat(skipPotBalance(guild)).isEqualTo(1); // non-refundable: coin sits in SKIP_POT (#13)
  }

  @Test
  void secondContributionForTheSameRunIsRefusedWithNoCharge() {
    long guild = uid();
    long member = uid();
    configPort.setGate(guild, false);
    seedBalance(guild, member, 5);
    setupCurrentGame(guild, daysAgo(2));

    service.contribute(req(guild, member));

    assertThatThrownBy(() -> service.contribute(req(guild, member)))
        .isInstanceOf(AlreadyContributedException.class);
    assertThat(jarCount(guild, WEEK)).isEqualTo(1); // PK enforced
    assertThat(ledgerPort.currentBalance(guild, member)).isEqualTo(4); // balance unchanged
  }

  @Test
  void zeroBalanceIsRefusedAndTheJarIsUnchanged() {
    long guild = uid();
    long member = uid();
    configPort.setGate(guild, false);
    setupCurrentGame(guild, daysAgo(2)); // member has no coins

    assertThatThrownBy(() -> service.contribute(req(guild, member)))
        .isInstanceOf(OverdrawException.class);
    assertThat(jarCount(guild, WEEK)).isZero();
  }

  @Test
  void jarClosedDuringDwellIsRefusedWithNoCharge() {
    long guild = uid();
    long member = uid();
    configPort.setGate(guild, false);
    seedBalance(guild, member, 5);
    setupCurrentGame(guild, Instant.now()); // became current just now → within dwell

    assertThatThrownBy(() -> service.contribute(req(guild, member)))
        .isInstanceOf(JarClosedException.class);
    assertThat(jarCount(guild, WEEK)).isZero();
    assertThat(ledgerPort.currentBalance(guild, member)).isEqualTo(5); // no charge
  }

  @Test
  void gateOnNonEarnerIsRefused() {
    long guild = uid();
    long member = uid();
    seedBalance(guild, member, 5); // has coins but never earned from the current game
    setupCurrentGame(guild, daysAgo(2)); // default config: gate ON

    assertThatThrownBy(() -> service.contribute(req(guild, member)))
        .isInstanceOf(NotEligibleToContributeException.class);
    assertThat(jarCount(guild, WEEK)).isZero();
  }

  @Test
  void gateOffAcceptsAnyMember() {
    long guild = uid();
    long member = uid();
    configPort.setGate(guild, false);
    seedBalance(guild, member, 5);
    setupCurrentGame(guild, daysAgo(2));

    ContributeResult result = service.contribute(req(guild, member));

    assertThat(result.charged()).isTrue();
    assertThat(jarCount(guild, WEEK)).isEqualTo(1);
  }

  // --- setup helpers ---

  private static ContributeRequest req(long guild, long member) {
    return new ContributeRequest(guild, member, uid(), Instant.now());
  }

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

  private int skipJarMovementCount(long guild, long member) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM coin_movement WHERE guild_id = ? AND member_id = ?"
                + " AND type = 'SKIP_JAR'",
            Integer.class,
            guild,
            member);
    return n == null ? 0 : n;
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
