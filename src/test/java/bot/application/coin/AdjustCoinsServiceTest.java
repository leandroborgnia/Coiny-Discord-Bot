package bot.application.coin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bot.application.coin.AdjustCoinsResult.Outcome;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.AppendResult;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.ModeratorNotAuthorizedException;
import bot.domain.coin.ModeratorRoleNotConfiguredException;
import bot.domain.coin.MovementRecord;
import bot.domain.coin.NonPositiveAmountException;
import bot.domain.coin.OverdrawException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdjustCoinsServiceTest {

  private static final long GUILD = 100L;
  private static final long MODERATOR = 7L;
  private static final long MEMBER = 9L;
  private static final long MOD_ROLE = 555L;
  private static final long INTERACTION = 123456L;

  @Mock private CoinLedgerPort ledgerPort;
  @Mock private GuildCoinConfigPort configPort;

  private AdjustCoinsService service;

  @BeforeEach
  void setUp() {
    service = new AdjustCoinsService(ledgerPort, configPort);
  }

  @Test
  void grantByRoleHolderApplies() {
    when(configPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);
    when(ledgerPort.append(any(), any())).thenReturn(new AppendResult(record(50, 0), true));

    AdjustCoinsResult result =
        service.adjust(request(AdjustmentType.GRANT, 50, Set.of(MOD_ROLE), false));

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(result.newBalance()).isEqualTo(50);
    assertThat(result.creditedAmount()).isEqualTo(50);
    assertThat(result.forfeitedAmount()).isZero();
    verify(ledgerPort).lockAccount(GUILD, MEMBER);
  }

  @Test
  void grantOverCapForfeitsRemainder() {
    when(configPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(80);
    when(ledgerPort.append(any(), any())).thenReturn(new AppendResult(record(20, 30), true));

    AdjustCoinsResult result = service.adjust(request(AdjustmentType.GRANT, 50, Set.of(), true));

    assertThat(result.newBalance()).isEqualTo(100);
    assertThat(result.creditedAmount()).isEqualTo(20);
    assertThat(result.forfeitedAmount()).isEqualTo(30);
  }

  @Test
  void adminMayAdjustWithoutTheRole() {
    when(configPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);
    when(ledgerPort.append(any(), any())).thenReturn(new AppendResult(record(10, 0), true));

    AdjustCoinsResult result = service.adjust(request(AdjustmentType.GRANT, 10, Set.of(), true));

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
  }

  @Test
  void unconfiguredRoleFailsClosed() {
    when(configPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, null, 12));

    assertThatThrownBy(() -> service.adjust(request(AdjustmentType.GRANT, 10, Set.of(), true)))
        .isInstanceOf(ModeratorRoleNotConfiguredException.class);
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void callerWithoutRoleOrAdminIsUnauthorized() {
    when(configPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));

    assertThatThrownBy(
            () -> service.adjust(request(AdjustmentType.GRANT, 10, Set.of(1L, 2L), false)))
        .isInstanceOf(ModeratorNotAuthorizedException.class);
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void nonPositiveAmountRejectedBeforeAnyPortCall() {
    assertThatThrownBy(
            () -> service.adjust(request(AdjustmentType.GRANT, 0, Set.of(MOD_ROLE), true)))
        .isInstanceOf(NonPositiveAmountException.class);
    verify(configPort, never()).get(anyLong());
  }

  @Test
  void overdrawDeductionThrowsAndAppendsNothing() {
    when(configPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(10);

    assertThatThrownBy(() -> service.adjust(request(AdjustmentType.DEDUCTION, 50, Set.of(), true)))
        .isInstanceOf(OverdrawException.class);
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void duplicateInteractionReturnsOriginalWithoutWriting() {
    when(configPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 100));
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.of(record(40, 0)));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(40);

    AdjustCoinsResult result = service.adjust(request(AdjustmentType.GRANT, 40, Set.of(), true));

    assertThat(result.outcome()).isEqualTo(Outcome.DUPLICATE);
    assertThat(result.newBalance()).isEqualTo(40);
    verify(ledgerPort, never()).append(any(), any());
    verify(ledgerPort, never()).lockAccount(anyLong(), anyLong());
  }

  private static AdjustCoinsRequest request(
      AdjustmentType type, int amount, Set<Long> roleIds, boolean admin) {
    return new AdjustCoinsRequest(
        GUILD, MODERATOR, roleIds, admin, MEMBER, type, amount, "reason", INTERACTION);
  }

  private static MovementRecord record(int credited, int forfeited) {
    return new MovementRecord(
        1L,
        GUILD,
        MEMBER,
        MODERATOR,
        AdjustmentType.GRANT,
        credited + forfeited,
        credited,
        forfeited,
        "reason",
        INTERACTION,
        Instant.now());
  }
}
