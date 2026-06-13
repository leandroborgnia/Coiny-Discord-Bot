package bot.infrastructure.persistence.participation;

import static org.assertj.core.api.Assertions.assertThat;

import bot.application.participation.AccrueParticipationRequest;
import bot.application.participation.AccrueParticipationResult;
import bot.application.participation.AccrueParticipationService;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.participation.ParticipationAccrual;
import bot.domain.participation.ParticipationAccrualPort;
import bot.domain.participation.ParticipationConfigPort;
import bot.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises participation accrual against real Postgres: the accrual upsert round-trip, the
 * negative-namespaced drop-id sequence, no double-credit under a replayed tick, and cap forfeiture
 * posting a balanced grant that leaves the derived balance pinned at the cap.
 */
class JpaParticipationAccrualAdapterTest extends AbstractPostgresIntegrationTest {

  @Autowired private ParticipationAccrualPort accrualPort;
  @Autowired private ParticipationConfigPort participationConfigPort;
  @Autowired private GuildCoinConfigPort guildCoinConfigPort;
  @Autowired private CoinLedgerPort ledgerPort;
  @Autowired private AccrueParticipationService accrueService;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void accrualUpsertRoundTrips() {
    long guild = uid();
    long member = uid();
    Instant sampledAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    tx(() -> accrualPort.upsert(guild, member, 90, sampledAt));

    ParticipationAccrual acc = accrualPort.get(guild, member);
    assertThat(acc.bankedSeconds()).isEqualTo(90);
    assertThat(acc.lastSampledAt()).isEqualTo(sampledAt);

    // A second upsert overwrites, not appends.
    tx(() -> accrualPort.upsert(guild, member, 25, sampledAt));
    assertThat(accrualPort.get(guild, member).bankedSeconds()).isEqualTo(25);
  }

  @Test
  void absentAccrualReadsAsZeroAndNull() {
    ParticipationAccrual acc = accrualPort.get(uid(), uid());
    assertThat(acc.bankedSeconds()).isZero();
    assertThat(acc.lastSampledAt()).isNull();
  }

  @Test
  void dropIdsAreNegativeAndCollisionFree() {
    Set<Long> ids = new HashSet<>();
    tx(
        () -> {
          for (int i = 0; i < 5; i++) {
            long id = accrualPort.nextDropId();
            assertThat(id).isNegative();
            ids.add(id);
          }
        });
    assertThat(ids).hasSize(5);
  }

  @Test
  void replayedTickWithTheSameInstantDoesNotDoubleCredit() {
    long guild = uid();
    long member = uid();
    guildCoinConfigPort.upsert(guild, null, 1000); // cap well above the drop
    participationConfigPort.setRate(guild, 1, 1); // 1 coin per 1 minute (threshold 60s)
    Instant now = Instant.now();
    tx(() -> accrualPort.upsert(guild, member, 0, now.minusSeconds(90)));

    AccrueParticipationResult first =
        accrueService.accrue(new AccrueParticipationRequest(guild, member, now));
    AccrueParticipationResult replay =
        accrueService.accrue(new AccrueParticipationRequest(guild, member, now));

    assertThat(first.dropsMinted()).isEqualTo(1);
    assertThat(replay.dropsMinted()).isZero(); // same instant → 0 elapsed → no re-credit
    assertThat(ledgerPort.currentBalance(guild, member)).isEqualTo(1);
  }

  @Test
  void capForfeitureCreditsHeadroomAndPinsBalanceAtCap() {
    long guild = uid();
    long member = uid();
    guildCoinConfigPort.upsert(guild, null, 12); // cap 12
    participationConfigPort.setRate(guild, 1, 20); // one drop is worth 20 coins
    Instant now = Instant.now();
    tx(() -> accrualPort.upsert(guild, member, 0, now.minusSeconds(90)));

    AccrueParticipationResult result =
        accrueService.accrue(new AccrueParticipationRequest(guild, member, now));

    assertThat(result.coinsCredited()).isEqualTo(12);
    assertThat(result.coinsForfeited()).isEqualTo(8);
    assertThat(ledgerPort.currentBalance(guild, member)).isEqualTo(12); // pinned at the cap
  }

  private void tx(Runnable work) {
    new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
