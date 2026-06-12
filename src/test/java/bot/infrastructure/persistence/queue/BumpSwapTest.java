package bot.infrastructure.persistence.queue;

import static org.assertj.core.api.Assertions.assertThat;

import bot.application.queue.BumpGameRequest;
import bot.application.queue.BumpGameResult;
import bot.application.queue.BumpGameService;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPolicy;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.infrastructure.persistence.coin.JpaCoinLedgerAdapter;
import bot.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end: a bump is a single swap that preserves every other position, posts a balanced {@code
 * QUEUE_BUMP} MEMBER↔POT movement, and accrues the cost on the slot's {@code coins_spent}.
 */
class BumpSwapTest extends AbstractPostgresIntegrationTest {

  @Autowired private BumpGameService bumpService;
  @Autowired private JpaCoinLedgerAdapter coinAdapter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void bumpSwapsUpByOnePreservingOthersAndPostsQueueBump() {
    long guild = uid();
    long topMember = uid();
    long me = uid();
    long tailMember = uid();
    long slotTop = insertSlot(guild, topMember, 1);
    long slotMe = insertSlot(guild, me, 2);
    long slotTail = insertSlot(guild, tailMember, 3);
    seedBalance(guild, me, 10);

    BumpGameResult result = bumpService.bump(new BumpGameRequest(guild, me, uid()));

    assertThat(result.outcome()).isEqualTo(BumpGameResult.Outcome.BUMPED);
    assertThat(result.newPosition()).isEqualTo(1);

    // Single swap: me 2->1, former top 1->2; the unrelated tail entry is untouched.
    assertThat(positionOf(slotMe)).isEqualTo(1);
    assertThat(positionOf(slotTop)).isEqualTo(2);
    assertThat(positionOf(slotTail)).isEqualTo(3);

    // Balanced MEMBER -> POT movement; the cost accrues on the slot.
    assertThat(coinAdapter.currentBalance(guild, me)).isEqualTo(9);
    assertThat(potBalance(guild)).isEqualTo(1);
    assertThat(movementTypes(guild, me)).contains("QUEUE_BUMP");
    assertThat(coinsSpentOf(slotMe)).isEqualTo(2); // 1 seeded + 1 bump (refundable on withdraw)
  }

  private long insertSlot(long guild, long member, int position) {
    return jdbc.queryForObject(
        "INSERT INTO queue_entry (guild_id, proposer_member_id, status, position, game_identity,"
            + " game_name, coins_spent, propose_interaction_id) VALUES (?, ?, 'QUEUED', ?, ?, ?, 1, ?)"
            + " RETURNING id",
        Long.class,
        guild,
        member,
        position,
        "name:game" + member,
        "Game" + member,
        uid());
  }

  private void seedBalance(long guild, long member, int amount) {
    new TransactionTemplate(txManager)
        .executeWithoutResult(
            status -> {
              PostingPlan plan = CoinLedgerPolicy.planGrant(guild, member, 0, amount, 1000);
              coinAdapter.append(
                  new NewMovement(
                      guild,
                      member,
                      member,
                      AdjustmentType.GRANT,
                      plan.requested(),
                      plan.credited(),
                      plan.forfeited(),
                      "seed",
                      uid()),
                  plan);
            });
  }

  private int positionOf(long slotId) {
    Integer p =
        jdbc.queryForObject("SELECT position FROM queue_entry WHERE id = ?", Integer.class, slotId);
    return p == null ? -1 : p;
  }

  private int coinsSpentOf(long slotId) {
    Integer c =
        jdbc.queryForObject(
            "SELECT coins_spent FROM queue_entry WHERE id = ?", Integer.class, slotId);
    return c == null ? 0 : c;
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

  private List<String> movementTypes(long guild, long member) {
    return jdbc.queryForList(
        "SELECT type FROM coin_movement WHERE guild_id = ? AND member_id = ?",
        String.class,
        guild,
        member);
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
