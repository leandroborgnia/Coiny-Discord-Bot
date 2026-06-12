package bot.infrastructure.persistence.queue;

import static org.assertj.core.api.Assertions.assertThat;

import bot.application.queue.ProposeGameRequest;
import bot.application.queue.ProposeGameResult;
import bot.application.queue.ProposeGameService;
import bot.application.queue.WithdrawGameRequest;
import bot.application.queue.WithdrawGameResult;
import bot.application.queue.WithdrawGameService;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPolicy;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.domain.queue.CapturedGame;
import bot.infrastructure.persistence.coin.JpaCoinLedgerAdapter;
import bot.support.AbstractPostgresIntegrationTest;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end: propose and withdraw post balanced MEMBER↔POT movements through the shared coin
 * ledger, and {@code /balance}'s derived SUM reflects them (incl. the refund reversal). One
 * economy, one ledger — the pot is just another account.
 */
class QueuePotLedgerTest extends AbstractPostgresIntegrationTest {

  @Autowired private ProposeGameService proposeService;
  @Autowired private WithdrawGameService withdrawService;
  @Autowired private JpaCoinLedgerAdapter coinAdapter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void proposeChargesThePotAndWithdrawRefundsItBalanced() {
    long guild = uid();
    long a = uid();
    long b = uid();
    seedBalance(guild, a, 10);
    seedBalance(guild, b, 10);

    // A proposes on an empty server -> instant-pop; 1 coin moves MEMBER -> POT.
    ProposeGameResult ra =
        proposeService.propose(new ProposeGameRequest(guild, a, game("Hades"), uid()));
    assertThat(ra.outcome()).isEqualTo(ProposeGameResult.Outcome.INSTANT_POPPED);
    assertThat(coinAdapter.currentBalance(guild, a)).isEqualTo(9);
    assertThat(potBalance(guild)).isEqualTo(1);

    // B proposes -> normal queued; another coin MEMBER -> POT.
    ProposeGameResult rb =
        proposeService.propose(new ProposeGameRequest(guild, b, game("Celeste"), uid()));
    assertThat(rb.outcome()).isEqualTo(ProposeGameResult.Outcome.PROPOSED);
    assertThat(coinAdapter.currentBalance(guild, b)).isEqualTo(9);
    assertThat(potBalance(guild)).isEqualTo(2);

    // B withdraws -> exactly the coins spent are refunded POT -> MEMBER.
    WithdrawGameResult w = withdrawService.withdraw(new WithdrawGameRequest(guild, b, uid()));
    assertThat(w.outcome()).isEqualTo(WithdrawGameResult.Outcome.WITHDRAWN);
    assertThat(w.refunded()).isEqualTo(1);
    assertThat(coinAdapter.currentBalance(guild, b)).isEqualTo(10);
    assertThat(potBalance(guild)).isEqualTo(1); // A's spend remains pooled
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

  private int potBalance(long guild) {
    Long sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM coin_ledger_entry"
                + " WHERE guild_id = ? AND account = 'POT'",
            Long.class,
            guild);
    return sum == null ? 0 : sum.intValue();
  }

  private static CapturedGame game(String name) {
    return CapturedGame.ofName(name);
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
