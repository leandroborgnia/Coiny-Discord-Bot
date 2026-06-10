package bot.infrastructure.persistence.coin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPolicy;
import bot.domain.coin.NewMovement;
import bot.domain.coin.PostingPlan;
import bot.support.AbstractPostgresIntegrationTest;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the Postgres-enforced ledger invariants: append-only (I1), balanced (I2), non-negative
 * (I3).
 */
class CoinLedgerTriggersTest extends AbstractPostgresIntegrationTest {

  @Autowired private JpaCoinLedgerAdapter adapter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void updateAndDeleteAreRejectedOnLedgerTables() {
    long guild = uid();
    long member = uid();
    long interaction = uid();
    PostingPlan plan = CoinLedgerPolicy.planGrant(guild, member, 0, 30, 100);
    new TransactionTemplate(txManager)
        .executeWithoutResult(
            s ->
                adapter.append(
                    new NewMovement(
                        guild, member, 7L, AdjustmentType.GRANT, 30, 30, 0, "r", interaction),
                    plan));

    Long movementId =
        jdbc.queryForObject(
            "SELECT id FROM coin_movement WHERE interaction_id = ?", Long.class, interaction);
    Long entryId =
        jdbc.queryForObject(
            "SELECT id FROM coin_ledger_entry WHERE movement_id = ? LIMIT 1",
            Long.class,
            movementId);

    assertThatThrownBy(
            () -> jdbc.update("UPDATE coin_ledger_entry SET amount = 999 WHERE id = ?", entryId))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM coin_movement WHERE id = ?", movementId))
        .hasMessageContaining("append-only");
  }

  @Test
  void unbalancedMovementIsRejectedAtCommit() {
    long guild = uid();
    long interaction = uid();

    assertThatThrownBy(
            () ->
                new TransactionTemplate(txManager)
                    .executeWithoutResult(
                        s -> {
                          Long movementId = insertMovement(guild, uid(), interaction);
                          // single, unbalanced entry (sum != 0)
                          jdbc.update(
                              "INSERT INTO coin_ledger_entry (movement_id, guild_id, account, member_id, amount)"
                                  + " VALUES (?,?, 'TREASURY', NULL, 5)",
                              movementId,
                              guild);
                        }))
        .hasStackTraceContaining("not balanced");
  }

  @Test
  void movementDrivingBalanceNegativeIsRejectedAtCommit() {
    long guild = uid();
    long member = uid();
    long interaction = uid();

    assertThatThrownBy(
            () ->
                new TransactionTemplate(txManager)
                    .executeWithoutResult(
                        s -> {
                          Long movementId = insertMovement(guild, member, interaction);
                          // balanced (sum 0) but drives the member negative
                          jdbc.update(
                              "INSERT INTO coin_ledger_entry (movement_id, guild_id, account, member_id, amount)"
                                  + " VALUES (?,?, 'MEMBER', ?, -5)",
                              movementId,
                              guild,
                              member);
                          jdbc.update(
                              "INSERT INTO coin_ledger_entry (movement_id, guild_id, account, member_id, amount)"
                                  + " VALUES (?,?, 'TREASURY', NULL, 5)",
                              movementId,
                              guild);
                        }))
        .hasStackTraceContaining("negative");
  }

  private Long insertMovement(long guild, long member, long interaction) {
    return jdbc.queryForObject(
        "INSERT INTO coin_movement"
            + " (guild_id, member_id, moderator_id, type, requested_amount, credited_amount,"
            + " forfeited_amount, interaction_id)"
            + " VALUES (?,?,?, 'GRANT', 5, 5, 0, ?) RETURNING id",
        Long.class,
        guild,
        member,
        7L,
        interaction);
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
