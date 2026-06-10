package bot.infrastructure.persistence.coin;

import static org.assertj.core.api.Assertions.assertThat;

import bot.domain.coin.AdjustmentType;
import bot.domain.coin.AppendResult;
import bot.domain.coin.CoinLedgerPolicy;
import bot.domain.coin.MovementRecord;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the ledger adapter against real Postgres: append, derived balance, history, isolation.
 */
class JpaCoinLedgerAdapterTest extends AbstractPostgresIntegrationTest {

  private static final long MOD = 7L;
  private static final int CAP = 100;

  @Autowired private JpaCoinLedgerAdapter adapter;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void grantAppendsBalancedMovementAndDerivesBalance() {
    long guild = uid();
    long member = uid();

    AppendResult result = grant(guild, member, 0, 50, uid());

    assertThat(result.inserted()).isTrue();
    assertThat(adapter.currentBalance(guild, member)).isEqualTo(50);
    assertThat(adapter.findByInteractionId(result.movement().interactionId())).isPresent();
    assertThat(adapter.recentHistory(guild, member, 10)).hasSize(1);
  }

  @Test
  void balanceReconcilesAcrossMixedMovementsNewestFirst() {
    long guild = uid();
    long member = uid();

    grant(guild, member, 0, 50, uid());
    deduct(guild, member, 50, 20, uid());

    assertThat(adapter.currentBalance(guild, member)).isEqualTo(30);
    List<MovementRecord> history = adapter.recentHistory(guild, member, 10);
    assertThat(history).hasSize(2);
    assertThat(history.get(0).type()).isEqualTo(AdjustmentType.DEDUCTION); // newest first
    assertThat(history.get(1).type()).isEqualTo(AdjustmentType.GRANT);
  }

  @Test
  void balancesAreIsolatedPerGuild() {
    long guildA = uid();
    long guildB = uid();
    long member = uid();

    grant(guildA, member, 0, 40, uid());

    assertThat(adapter.currentBalance(guildA, member)).isEqualTo(40);
    assertThat(adapter.currentBalance(guildB, member)).isZero();
    assertThat(adapter.recentHistory(guildB, member, 10)).isEmpty();
  }

  private AppendResult grant(
      long guild, long member, int balanceBefore, int amount, long interaction) {
    PostingPlan plan = CoinLedgerPolicy.planGrant(guild, member, balanceBefore, amount, CAP);
    return append(guild, member, AdjustmentType.GRANT, plan, interaction);
  }

  private AppendResult deduct(
      long guild, long member, int balanceBefore, int amount, long interaction) {
    PostingPlan plan = CoinLedgerPolicy.planDeduction(guild, member, balanceBefore, amount);
    return append(guild, member, AdjustmentType.DEDUCTION, plan, interaction);
  }

  private AppendResult append(
      long guild, long member, AdjustmentType type, PostingPlan plan, long interaction) {
    NewMovement movement =
        new NewMovement(
            guild,
            member,
            MOD,
            type,
            plan.requested(),
            plan.credited(),
            plan.forfeited(),
            "reason",
            interaction);
    return new TransactionTemplate(txManager).execute(s -> adapter.append(movement, plan));
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
