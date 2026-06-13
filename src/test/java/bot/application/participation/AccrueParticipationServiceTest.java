package bot.application.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bot.application.participation.AccrueParticipationResult.Outcome;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.NewMovement;
import bot.domain.participation.GuildParticipationConfig;
import bot.domain.participation.ParticipationAccrual;
import bot.domain.participation.ParticipationAccrualPort;
import bot.domain.participation.ParticipationConfigPort;
import bot.domain.participation.ParticipationRate;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccrueParticipationServiceTest {

  private static final long GUILD = 100L;
  private static final long MEMBER = 9L;
  private static final long MOD_ROLE = 555L;
  private static final Instant NOW = Instant.parse("2026-06-13T12:00:00Z");

  @Mock private ParticipationConfigPort configPort;
  @Mock private ParticipationAccrualPort accrualPort;
  @Mock private CoinLedgerPort ledgerPort;
  @Mock private GuildCoinConfigPort coinConfigPort;

  private AccrueParticipationService service;

  @BeforeEach
  void setUp() {
    service =
        new AccrueParticipationService(configPort, accrualPort, ledgerPort, coinConfigPort, "PT2M");
  }

  @Test
  void pausesAtCapWithoutBankingOrMinting() {
    when(coinConfigPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 12));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(12);
    when(accrualPort.get(GUILD, MEMBER))
        .thenReturn(new ParticipationAccrual(GUILD, MEMBER, 30, NOW.minusSeconds(90)));

    AccrueParticipationResult result = service.accrue(request());

    assertThat(result.outcome()).isEqualTo(Outcome.PAUSED_AT_CAP);
    verify(ledgerPort).lockAccount(GUILD, MEMBER);
    verify(accrualPort).upsert(GUILD, MEMBER, 30, NOW); // banked unchanged, clock advanced
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void freshSessionWhenGapExceedsMaxGapBanksNothing() {
    when(coinConfigPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);
    when(accrualPort.get(GUILD, MEMBER))
        .thenReturn(new ParticipationAccrual(GUILD, MEMBER, 10, NOW.minusSeconds(300)));

    AccrueParticipationResult result = service.accrue(request());

    assertThat(result.outcome()).isEqualTo(Outcome.FRESH_SESSION);
    verify(accrualPort).upsert(GUILD, MEMBER, 10, NOW); // remainder kept, only clock advanced
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void mintsASingleDropAndCarriesTheRemainder() {
    when(coinConfigPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);
    when(accrualPort.get(GUILD, MEMBER))
        .thenReturn(new ParticipationAccrual(GUILD, MEMBER, 0, NOW.minusSeconds(90)));
    when(configPort.get(GUILD))
        .thenReturn(new GuildParticipationConfig(GUILD, new ParticipationRate(1, 1), false));
    when(accrualPort.nextDropId()).thenReturn(-1L);

    AccrueParticipationResult result = service.accrue(request());

    assertThat(result.outcome()).isEqualTo(Outcome.MINTED);
    assertThat(result.dropsMinted()).isEqualTo(1);
    assertThat(result.coinsCredited()).isEqualTo(1);
    assertThat(result.coinsForfeited()).isZero();
    verify(ledgerPort, times(1)).append(any(), any());
    verify(accrualPort).upsert(GUILD, MEMBER, 30, NOW); // 90s elapsed − 60s threshold = 30s banked

    ArgumentCaptor<NewMovement> captor = ArgumentCaptor.forClass(NewMovement.class);
    verify(ledgerPort).append(captor.capture(), any());
    NewMovement movement = captor.getValue();
    assertThat(movement.type()).isEqualTo(AdjustmentType.PARTICIPATION);
    assertThat(movement.moderatorId()).isEqualTo(MEMBER); // self-initiated
    assertThat(movement.interactionId()).isNegative();
  }

  @Test
  void mintsMultipleWholeDropsInOneTick() {
    when(coinConfigPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);
    when(accrualPort.get(GUILD, MEMBER))
        .thenReturn(new ParticipationAccrual(GUILD, MEMBER, 30, NOW.minusSeconds(100)));
    when(configPort.get(GUILD))
        .thenReturn(new GuildParticipationConfig(GUILD, new ParticipationRate(1, 1), false));
    when(accrualPort.nextDropId()).thenReturn(-1L, -2L);

    AccrueParticipationResult result = service.accrue(request());

    assertThat(result.dropsMinted()).isEqualTo(2); // (30 + 100) / 60 = 2
    assertThat(result.coinsCredited()).isEqualTo(2);
    verify(ledgerPort, times(2)).append(any(), any());
    verify(accrualPort).upsert(GUILD, MEMBER, 10, NOW); // 130 − 2*60 = 10 banked
  }

  @Test
  void capCrossingForfeitsAndStopsMintingFurtherDrops() {
    when(coinConfigPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 12));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);
    // 30 + 100 = 130s banked → two whole drops are available, but the first hits the cap.
    when(accrualPort.get(GUILD, MEMBER))
        .thenReturn(new ParticipationAccrual(GUILD, MEMBER, 30, NOW.minusSeconds(100)));
    when(configPort.get(GUILD))
        .thenReturn(new GuildParticipationConfig(GUILD, new ParticipationRate(1, 20), false));
    when(accrualPort.nextDropId()).thenReturn(-1L);

    AccrueParticipationResult result = service.accrue(request());

    assertThat(result.dropsMinted()).isEqualTo(1); // broke after the forfeiting drop
    assertThat(result.coinsCredited()).isEqualTo(12);
    assertThat(result.coinsForfeited()).isEqualTo(8);
    verify(ledgerPort, times(1)).append(any(), any());
    verify(accrualPort)
        .upsert(GUILD, MEMBER, 70, NOW); // 130 − 60 = 70 banked (second drop unminted)
  }

  @Test
  void banksTimeWithoutMintingBelowTheThreshold() {
    when(coinConfigPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);
    when(accrualPort.get(GUILD, MEMBER))
        .thenReturn(new ParticipationAccrual(GUILD, MEMBER, 10, NOW.minusSeconds(20)));
    when(configPort.get(GUILD))
        .thenReturn(new GuildParticipationConfig(GUILD, new ParticipationRate(1, 1), false));

    AccrueParticipationResult result = service.accrue(request());

    assertThat(result.outcome()).isEqualTo(Outcome.ACCRUED);
    assertThat(result.dropsMinted()).isZero();
    verify(ledgerPort, never()).append(any(), any());
    verify(accrualPort).upsert(GUILD, MEMBER, 30, NOW); // 10 + 20 = 30s banked, below 60s threshold
    verify(accrualPort, never()).nextDropId();
  }

  private static AccrueParticipationRequest request() {
    return new AccrueParticipationRequest(GUILD, MEMBER, NOW);
  }
}
