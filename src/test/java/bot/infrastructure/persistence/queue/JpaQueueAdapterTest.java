package bot.infrastructure.persistence.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bot.domain.queue.CapturedGame;
import bot.domain.queue.GameIdentity;
import bot.domain.queue.NewSlot;
import bot.domain.queue.QueueSlot;
import bot.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the queue adapter against real Postgres: tail append, partial-unique, idempotency,
 * concurrency, per-guild isolation, departed-proposer retention, and position re-densing.
 */
class JpaQueueAdapterTest extends AbstractPostgresIntegrationTest {

  @Autowired private JpaQueueAdapter adapter;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void appendGoesToTheTailInOrder() {
    long guild = uid();
    long m1 = uid();
    long m2 = uid();
    append(guild, m1, 1, uid());
    append(guild, m2, 2, uid());

    List<QueueSlot> queued = tx(() -> adapter.queued(guild));
    assertThat(queued).hasSize(2);
    assertThat(queued.get(0).proposerMemberId()).isEqualTo(m1);
    assertThat(queued.get(0).position()).isEqualTo(1);
    assertThat(queued.get(1).position()).isEqualTo(2);
  }

  @Test
  void atMostOneQueuedSlotPerMember() {
    long guild = uid();
    long member = uid();
    append(guild, member, 1, uid());

    assertThatThrownBy(() -> append(guild, member, 2, uid())).isInstanceOf(RuntimeException.class);
  }

  @Test
  void positionIsUniquePerGuild() {
    long guild = uid();
    append(guild, uid(), 1, uid());

    assertThatThrownBy(() -> append(guild, uid(), 1, uid())).isInstanceOf(RuntimeException.class);
  }

  @Test
  void sameProposeInteractionIsIdempotent() {
    long guild = uid();
    long member = uid();
    long interaction = uid();

    QueueSlot first = append(guild, member, 1, interaction);
    QueueSlot second = append(guild, member, 1, interaction);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(tx(() -> adapter.queued(guild))).hasSize(1);
  }

  @Test
  void concurrentSameInteractionInsertsExactlyOnce() throws Exception {
    long guild = uid();
    long member = uid();
    long interaction = uid();

    runConcurrently(
        () -> append(guild, member, 1, interaction), () -> append(guild, member, 1, interaction));

    assertThat(tx(() -> adapter.queued(guild))).hasSize(1);
  }

  @Test
  void queuesAreIsolatedPerGuild() {
    long guildA = uid();
    long guildB = uid();
    long member = uid();
    append(guildA, member, 1, uid());
    append(guildB, member, 1, uid());

    assertThat(tx(() -> adapter.queued(guildA))).hasSize(1);
    assertThat(tx(() -> adapter.queued(guildB))).hasSize(1);
    assertThat(tx(() -> adapter.ownQueued(guildA, member))).isPresent();
  }

  @Test
  void departedProposerSlotIsRetainedAndRendersByStoredId() {
    long guild = uid();
    long member = uid();
    QueueSlot appended = append(guild, member, 1, uid());

    // Nothing joins to live membership, so the row persists and renders by its stored proposer id.
    QueueSlot read = tx(() -> adapter.queued(guild)).get(0);
    assertThat(read.id()).isEqualTo(appended.id());
    assertThat(read.proposerMemberId()).isEqualTo(member);
  }

  @Test
  void shiftUpRedensesPositionsAfterAMiddleRemoval() {
    long guild = uid();
    long m1 = uid();
    long m2 = uid();
    long m3 = uid();
    append(guild, m1, 1, uid());
    QueueSlot middle = append(guild, m2, 2, uid());
    append(guild, m3, 3, uid());

    tx(
        () -> {
          adapter.lockQueue(guild);
          adapter.withdraw(middle.id());
          adapter.shiftUp(guild);
          return null;
        });

    List<QueueSlot> queued = tx(() -> adapter.queued(guild));
    assertThat(queued).extracting(QueueSlot::position).containsExactly(1, 2);
    assertThat(queued).extracting(QueueSlot::proposerMemberId).containsExactly(m1, m3);
  }

  private QueueSlot append(long guild, long member, int position, long interaction) {
    return tx(
        () -> {
          adapter.lockQueue(guild);
          CapturedGame game = CapturedGame.ofName("Game-" + member);
          return adapter.append(
              NewSlot.queued(
                  guild,
                  member,
                  game,
                  GameIdentity.of(game),
                  UUID.randomUUID(),
                  position,
                  1,
                  interaction));
        });
  }

  private <T> T tx(Supplier<T> work) {
    return new TransactionTemplate(txManager).execute(status -> work.get());
  }

  private void runConcurrently(Runnable a, Runnable b) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Object>> tasks =
          List.of(
              () -> {
                a.run();
                return null;
              },
              () -> {
                b.run();
                return null;
              });
      for (Future<?> f : pool.invokeAll(tasks)) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
