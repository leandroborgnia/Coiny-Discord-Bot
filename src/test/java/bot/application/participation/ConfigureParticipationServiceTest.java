package bot.application.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bot.application.participation.ConfigureParticipationRequest.Op;
import bot.domain.coin.GuildCoinConfig;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.ModeratorNotAuthorizedException;
import bot.domain.coin.ModeratorRoleNotConfiguredException;
import bot.domain.participation.DesignatedChannelPort;
import bot.domain.participation.GuildParticipationConfig;
import bot.domain.participation.ParticipationConfigPort;
import bot.domain.participation.ParticipationRate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfigureParticipationServiceTest {

  private static final long GUILD = 100L;
  private static final long ACTOR = 7L;
  private static final long MOD_ROLE = 555L;
  private static final long CHANNEL = 42L;

  @Mock private GuildCoinConfigPort guildCoinConfigPort;
  @Mock private ParticipationConfigPort participationConfigPort;
  @Mock private DesignatedChannelPort designatedChannelPort;

  private ConfigureParticipationService service;

  @BeforeEach
  void setUp() {
    service =
        new ConfigureParticipationService(
            guildCoinConfigPort, participationConfigPort, designatedChannelPort);
  }

  @Test
  void channelAddDelegatesAndReportsCount() {
    authorizedByRole();
    when(designatedChannelPort.list(GUILD)).thenReturn(List.of(CHANNEL));
    when(participationConfigPort.get(GUILD)).thenReturn(GuildParticipationConfig.defaults(GUILD));

    ParticipationConfigResult result =
        service.configure(channelAdd(Set.of(MOD_ROLE), false, CHANNEL));

    verify(designatedChannelPort).add(GUILD, CHANNEL);
    assertThat(result.designatedChannelCount()).isEqualTo(1);
  }

  @Test
  void channelAddIsRepeatableAndDelegatesEachTime() {
    authorizedByRole();
    when(designatedChannelPort.list(GUILD)).thenReturn(List.of(CHANNEL));
    when(participationConfigPort.get(GUILD)).thenReturn(GuildParticipationConfig.defaults(GUILD));

    service.configure(channelAdd(Set.of(MOD_ROLE), false, CHANNEL));
    service.configure(channelAdd(Set.of(MOD_ROLE), false, CHANNEL));

    verify(designatedChannelPort, times(2)).add(GUILD, CHANNEL); // dedup is the adapter's job
  }

  @Test
  void channelResetClearsTheSet() {
    authorizedByRole();
    when(designatedChannelPort.list(GUILD)).thenReturn(List.of());
    when(participationConfigPort.get(GUILD)).thenReturn(GuildParticipationConfig.defaults(GUILD));

    ParticipationConfigResult result =
        service.configure(
            new ConfigureParticipationRequest(
                GUILD, ACTOR, Set.of(MOD_ROLE), false, Op.CHANNEL_RESET, null, null, null, null));

    verify(designatedChannelPort).resetAll(GUILD);
    assertThat(result.designatedChannelCount()).isZero();
  }

  @Test
  void rateSetsAndEchoesTheNewRate() {
    authorizedByRole();
    when(designatedChannelPort.list(GUILD)).thenReturn(List.of());
    when(participationConfigPort.get(GUILD))
        .thenReturn(new GuildParticipationConfig(GUILD, new ParticipationRate(30, 2), false));

    ParticipationConfigResult result = service.configure(rate(Set.of(MOD_ROLE), false, 30, 2));

    verify(participationConfigPort).setRate(GUILD, 30, 2);
    assertThat(result.minutesPerDrop()).isEqualTo(30);
    assertThat(result.coinsPerDrop()).isEqualTo(2);
  }

  @Test
  void rateRejectsValuesBelowOne() {
    authorizedByRole();

    assertThatThrownBy(() -> service.configure(rate(Set.of(MOD_ROLE), false, 0, 1)))
        .isInstanceOf(IllegalArgumentException.class);
    verify(participationConfigPort, never()).setRate(anyLong(), anyInt(), anyInt());
  }

  @Test
  void freeProposalTurnsTheToggleOn() {
    authorizedByRole();
    when(designatedChannelPort.list(GUILD)).thenReturn(List.of());
    when(participationConfigPort.get(GUILD))
        .thenReturn(new GuildParticipationConfig(GUILD, ParticipationRate.defaults(), true));

    ParticipationConfigResult result =
        service.configure(freeProposal(Set.of(MOD_ROLE), false, true));

    verify(participationConfigPort).setFreeFirstProposal(GUILD, true);
    assertThat(result.freeFirstProposal()).isTrue();
  }

  @Test
  void freeProposalTurnsTheToggleOff() {
    authorizedByRole();
    when(designatedChannelPort.list(GUILD)).thenReturn(List.of());
    when(participationConfigPort.get(GUILD)).thenReturn(GuildParticipationConfig.defaults(GUILD));

    ParticipationConfigResult result =
        service.configure(freeProposal(Set.of(MOD_ROLE), false, false));

    verify(participationConfigPort).setFreeFirstProposal(GUILD, false);
    assertThat(result.freeFirstProposal()).isFalse();
  }

  @Test
  void freeProposalIsModeratorGated() {
    authorizedByRole();

    assertThatThrownBy(() -> service.configure(freeProposal(Set.of(1L, 2L), false, true)))
        .isInstanceOf(ModeratorNotAuthorizedException.class);
    verify(participationConfigPort, never()).setFreeFirstProposal(anyLong(), anyBoolean());
  }

  @Test
  void adminMayConfigureWithoutTheRole() {
    authorizedByRole();
    when(designatedChannelPort.list(GUILD)).thenReturn(List.of(CHANNEL));
    when(participationConfigPort.get(GUILD)).thenReturn(GuildParticipationConfig.defaults(GUILD));

    service.configure(channelAdd(Set.of(), true, CHANNEL));

    verify(designatedChannelPort).add(GUILD, CHANNEL);
  }

  @Test
  void unconfiguredRoleFailsClosed() {
    when(guildCoinConfigPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, null, 12));

    assertThatThrownBy(() -> service.configure(channelAdd(Set.of(MOD_ROLE), true, CHANNEL)))
        .isInstanceOf(ModeratorRoleNotConfiguredException.class);
    verifyNoWrites();
  }

  @Test
  void callerWithoutRoleOrAdminIsUnauthorizedAndChangesNothing() {
    authorizedByRole();

    assertThatThrownBy(() -> service.configure(channelAdd(Set.of(1L, 2L), false, CHANNEL)))
        .isInstanceOf(ModeratorNotAuthorizedException.class);
    verifyNoWrites();
  }

  private void authorizedByRole() {
    when(guildCoinConfigPort.get(GUILD)).thenReturn(new GuildCoinConfig(GUILD, MOD_ROLE, 12));
  }

  private void verifyNoWrites() {
    verify(designatedChannelPort, never()).add(anyLong(), anyLong());
    verify(designatedChannelPort, never()).resetAll(anyLong());
    verify(participationConfigPort, never()).setRate(anyLong(), anyInt(), anyInt());
  }

  private static ConfigureParticipationRequest channelAdd(
      Set<Long> roleIds, boolean admin, long channelId) {
    return new ConfigureParticipationRequest(
        GUILD, ACTOR, roleIds, admin, Op.CHANNEL_ADD, channelId, null, null, null);
  }

  private static ConfigureParticipationRequest rate(
      Set<Long> roleIds, boolean admin, int minutes, int coins) {
    return new ConfigureParticipationRequest(
        GUILD, ACTOR, roleIds, admin, Op.RATE, null, minutes, coins, null);
  }

  private static ConfigureParticipationRequest freeProposal(
      Set<Long> roleIds, boolean admin, boolean enabled) {
    return new ConfigureParticipationRequest(
        GUILD, ACTOR, roleIds, admin, Op.FREE_PROPOSAL, null, null, null, enabled);
  }
}
