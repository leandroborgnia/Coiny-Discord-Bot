package bot.application.coin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import bot.application.coin.BalanceView.MovementSummary;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.MovementRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoinQueryServiceTest {

  private static final long GUILD = 100L;
  private static final long MEMBER = 9L;

  @Mock private CoinLedgerPort ledgerPort;
  @Mock private GuildCoinConfigPort configPort;

  private CoinQueryService service;

  @BeforeEach
  void setUp() {
    service = new CoinQueryService(ledgerPort, configPort);
  }

  @Test
  void mapsBalanceCapAndHistory() {
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(30);
    when(configPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, 5L, 100));
    when(ledgerPort.recentHistory(GUILD, MEMBER, 10))
        .thenReturn(
            List.of(
                new MovementRecord(
                    2L,
                    GUILD,
                    MEMBER,
                    7L,
                    AdjustmentType.DEDUCTION,
                    20,
                    0,
                    0,
                    "penalty",
                    222L,
                    Instant.now()),
                new MovementRecord(
                    1L,
                    GUILD,
                    MEMBER,
                    7L,
                    AdjustmentType.GRANT,
                    50,
                    50,
                    0,
                    "win",
                    111L,
                    Instant.now())));

    BalanceView view = service.viewBalance(new ViewBalanceRequest(GUILD, MEMBER, 10));

    assertThat(view.balance()).isEqualTo(30);
    assertThat(view.cap()).isEqualTo(100);
    assertThat(view.recent()).hasSize(2);
    MovementSummary first = view.recent().get(0);
    assertThat(first.type()).isEqualTo(AdjustmentType.DEDUCTION);
    assertThat(first.requested()).isEqualTo(20);
  }

  @Test
  void emptyMemberReportsZeroAndNoHistory() {
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);
    when(configPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, null, 12));
    when(ledgerPort.recentHistory(GUILD, MEMBER, 10)).thenReturn(List.of());

    BalanceView view = service.viewBalance(new ViewBalanceRequest(GUILD, MEMBER, 10));

    assertThat(view.balance()).isZero();
    assertThat(view.recent()).isEmpty();
  }
}
