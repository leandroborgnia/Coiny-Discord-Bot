package bot.infrastructure.persistence.queue;

import static org.assertj.core.api.Assertions.assertThat;

import bot.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the upvote adapter against real Postgres: toggle add/remove, count scoped to a slot's
 * current appearance, one-or-zero per member, and replace-invalidates-upvotes (finding U3).
 */
class JpaUpvoteAdapterTest extends AbstractPostgresIntegrationTest {

  @Autowired private JpaUpvoteAdapter adapter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void toggleAddsThenRemovesAndCountReflectsIt() {
    long slot = insertSlot();
    UUID appearance = UUID.randomUUID();

    assertThat(tx(() -> adapter.toggle(slot, 1L, appearance))).isTrue();
    assertThat(tx(() -> adapter.count(slot, appearance))).isEqualTo(1);
    assertThat(tx(() -> adapter.toggle(slot, 1L, appearance))).isTrue();
    assertThat(tx(() -> adapter.count(slot, appearance))).isZero();
  }

  @Test
  void countIsScopedToTheAppearance() {
    long slot = insertSlot();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    tx(() -> adapter.toggle(slot, 1L, a));

    assertThat(tx(() -> adapter.count(slot, a))).isEqualTo(1);
    assertThat(tx(() -> adapter.count(slot, b))).isZero();
  }

  @Test
  void eachMemberCountsAtMostOnce() {
    long slot = insertSlot();
    UUID appearance = UUID.randomUUID();
    tx(() -> adapter.toggle(slot, 1L, appearance));
    tx(() -> adapter.toggle(slot, 2L, appearance));

    assertThat(tx(() -> adapter.count(slot, appearance))).isEqualTo(2);
  }

  @Test
  void replacedAppearanceShowsZeroYetAllowsRevoting() {
    long slot = insertSlot();
    UUID oldAppearance = UUID.randomUUID();
    UUID newAppearance = UUID.randomUUID();
    tx(() -> adapter.toggle(slot, 1L, oldAppearance));
    assertThat(tx(() -> adapter.count(slot, oldAppearance))).isEqualTo(1);

    // After a replace mints a new appearance, its count is 0 and the member can re-vote with no
    // primary-key collision; the stray old-appearance row is never counted for the new one (U3).
    assertThat(tx(() -> adapter.count(slot, newAppearance))).isZero();
    assertThat(tx(() -> adapter.toggle(slot, 1L, newAppearance))).isTrue();
    assertThat(tx(() -> adapter.count(slot, newAppearance))).isEqualTo(1);

    // resetForSlot cleans up the prior-appearance rows.
    tx(
        () -> {
          adapter.resetForSlot(slot);
          return null;
        });
    assertThat(tx(() -> adapter.count(slot, oldAppearance))).isZero();
    assertThat(tx(() -> adapter.count(slot, newAppearance))).isZero();
  }

  private long insertSlot() {
    long guild = uid();
    return jdbc.queryForObject(
        "INSERT INTO queue_entry (guild_id, proposer_member_id, status, position, game_identity,"
            + " game_name, coins_spent, propose_interaction_id) VALUES (?, ?, 'QUEUED', 1, ?, ?, 1, ?)"
            + " RETURNING id",
        Long.class,
        guild,
        uid(),
        "name:game",
        "Game",
        uid());
  }

  private <T> T tx(Supplier<T> work) {
    return new TransactionTemplate(txManager).execute(status -> work.get());
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
