package bot.application.coin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.InvalidCoinCapException;
import bot.domain.coin.ModeratorNotAuthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoinConfigServiceTest {

  private static final long GUILD = 100L;

  @Mock private GuildCoinConfigPort configPort;

  private CoinConfigService service;

  @BeforeEach
  void setUp() {
    service = new CoinConfigService(configPort);
  }

  @Test
  void adminSetsRoleAndCap() {
    when(configPort.upsert(GUILD, 5L, 50)).thenReturn(new GuildCoinConfig(GUILD, 5L, 50));

    CoinConfigResult result = service.configure(new ConfigureCoinsRequest(GUILD, true, 5L, 50));

    assertThat(result.moderatorRoleId()).isEqualTo(5L);
    assertThat(result.cap()).isEqualTo(50);
  }

  @Test
  void partialUpdateLeavesOtherSettingUnchanged() {
    when(configPort.upsert(GUILD, null, 25)).thenReturn(new GuildCoinConfig(GUILD, 5L, 25));

    CoinConfigResult result = service.configure(new ConfigureCoinsRequest(GUILD, true, null, 25));

    assertThat(result.moderatorRoleId()).isEqualTo(5L);
    assertThat(result.cap()).isEqualTo(25);
    verify(configPort).upsert(GUILD, null, 25);
  }

  @Test
  void nonAdminIsRejected() {
    assertThatThrownBy(() -> service.configure(new ConfigureCoinsRequest(GUILD, false, 5L, 50)))
        .isInstanceOf(ModeratorNotAuthorizedException.class);
    verify(configPort, never()).upsert(anyLong(), any(), any());
  }

  @Test
  void negativeCapIsRejected() {
    assertThatThrownBy(() -> service.configure(new ConfigureCoinsRequest(GUILD, true, null, -1)))
        .isInstanceOf(InvalidCoinCapException.class);
    verify(configPort, never()).upsert(anyLong(), any(), any());
  }
}
