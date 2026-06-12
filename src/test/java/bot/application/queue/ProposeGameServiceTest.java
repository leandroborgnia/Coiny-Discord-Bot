package bot.application.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bot.application.queue.ProposeGameResult.Outcome;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.queue.AnnouncementView;
import bot.domain.queue.CapturedGame;
import bot.domain.queue.CooldownPort;
import bot.domain.queue.GameIdentity;
import bot.domain.queue.GuildQueueConfig;
import bot.domain.queue.InsufficientCoinsException;
import bot.domain.queue.NotEligibleException;
import bot.domain.queue.QueueConfigPort;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.QueueStatus;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import bot.domain.queue.UpvotePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProposeGameServiceTest {

  private static final long GUILD = 100L;
  private static final long MEMBER = 9L;
  private static final long INTERACTION = 123456L;
  private static final CapturedGame GAME = CapturedGame.ofName("Hades");

  @Mock private QueuePort queuePort;
  @Mock private QueueConfigPort configPort;
  @Mock private CooldownPort cooldownPort;
  @Mock private RotationStatePort rotationPort;
  @Mock private UpvotePort upvotePort;
  @Mock private CoinLedgerPort ledgerPort;
  @Mock private AnnouncementAssembler announcementAssembler;

  private ProposeGameService service;

  @BeforeEach
  void setUp() {
    service =
        new ProposeGameService(
            queuePort,
            configPort,
            cooldownPort,
            rotationPort,
            upvotePort,
            ledgerPort,
            announcementAssembler);
  }

  @Test
  void noActivityGuardChangesNothing() {
    ProposeGameResult result =
        service.propose(new ProposeGameRequest(GUILD, MEMBER, null, INTERACTION));

    assertThat(result.outcome()).isEqualTo(Outcome.NO_ACTIVITY);
    verifyNoInteractions(queuePort, configPort, cooldownPort, rotationPort, upvotePort, ledgerPort);
  }

  @Test
  void duplicateReturnsExistingWithoutCharging() {
    when(queuePort.findByProposeInteraction(INTERACTION))
        .thenReturn(Optional.of(queuedSlot(5L, 3, 1)));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);

    ProposeGameResult result = propose();

    assertThat(result.outcome()).isEqualTo(Outcome.DUPLICATE);
    assertThat(result.position()).isEqualTo(3);
    verify(queuePort, never()).append(any());
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void replaceIsFreeAndResetsUpvotes() {
    when(queuePort.findByProposeInteraction(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.of(queuedSlot(7L, 2, 1)));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);

    ProposeGameResult result = propose();

    assertThat(result.outcome()).isEqualTo(Outcome.REPLACED);
    assertThat(result.coinsSpent()).isZero();
    verify(queuePort).replaceGame(eq(7L), eq(GAME), any(GameIdentity.class), any(UUID.class));
    verify(upvotePort).resetForSlot(7L);
    verify(queuePort, never()).append(any());
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void cooldownMakesProposerIneligible() {
    when(queuePort.findByProposeInteraction(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.empty());
    when(cooldownPort.gamesRemaining(GUILD, MEMBER)).thenReturn(2);

    assertThatThrownBy(this::propose)
        .isInstanceOf(NotEligibleException.class)
        .satisfies(e -> assertThat(((NotEligibleException) e).gamesRemaining()).isEqualTo(2));
    verify(queuePort, never()).append(any());
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void insufficientCoinsThrowsAndAppendsNothing() {
    when(queuePort.findByProposeInteraction(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.empty());
    when(cooldownPort.gamesRemaining(GUILD, MEMBER)).thenReturn(0);
    when(configPort.get(GUILD)).thenReturn(GuildQueueConfig.defaults(GUILD));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);

    assertThatThrownBy(this::propose).isInstanceOf(InsufficientCoinsException.class);
    verify(queuePort, never()).append(any());
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void firstProposalOnEmptyServerInstantPops() {
    when(queuePort.findByProposeInteraction(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.empty());
    when(cooldownPort.gamesRemaining(GUILD, MEMBER)).thenReturn(0);
    when(configPort.get(GUILD)).thenReturn(GuildQueueConfig.defaults(GUILD));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);
    when(rotationPort.get(GUILD)).thenReturn(new RotationState(GUILD, null, 0, null));
    when(queuePort.queued(GUILD)).thenReturn(List.of());
    when(queuePort.append(any())).thenReturn(playedSlot(1L, 0, 1));

    ProposeGameResult result = propose();

    assertThat(result.outcome()).isEqualTo(Outcome.INSTANT_POPPED);
    assertThat(result.instantPop()).isTrue();
    assertThat(result.coinsSpent()).isEqualTo(1);
    assertThat(result.newBalance()).isEqualTo(4);
    verify(rotationPort)
        .recordDesignation(eq(GUILD), eq(0), eq(1L), any(GameIdentity.class), any());
    verify(rotationPort).bootstrap(eq(GUILD), eq(1L), any());
    verify(cooldownPort).set(GUILD, MEMBER, 0);
    verify(ledgerPort).append(any(), any());
  }

  @Test
  void instantPopWithAnnouncementChannelReturnsTheAnnouncement() {
    when(queuePort.findByProposeInteraction(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.empty());
    when(cooldownPort.gamesRemaining(GUILD, MEMBER)).thenReturn(0);
    when(configPort.get(GUILD)).thenReturn(new GuildQueueConfig(GUILD, 1, 1, 555L, null, null));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);
    when(rotationPort.get(GUILD)).thenReturn(new RotationState(GUILD, null, 0, null));
    when(queuePort.queued(GUILD)).thenReturn(List.of());
    when(queuePort.append(any())).thenReturn(playedSlot(1L, 0, 1));
    when(announcementAssembler.assemble(GUILD))
        .thenReturn(Optional.of(new AnnouncementView(GUILD, "Hades", MEMBER, null, List.of())));

    ProposeGameResult result = propose();

    assertThat(result.outcome()).isEqualTo(Outcome.INSTANT_POPPED);
    assertThat(result.announcement()).isPresent();
  }

  @Test
  void normalProposalAppendsAtTailAndCharges() {
    when(queuePort.findByProposeInteraction(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.empty());
    when(cooldownPort.gamesRemaining(GUILD, MEMBER)).thenReturn(0);
    when(configPort.get(GUILD)).thenReturn(GuildQueueConfig.defaults(GUILD));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);
    when(rotationPort.get(GUILD)).thenReturn(new RotationState(GUILD, 99L, 1, Instant.now()));
    when(queuePort.queued(GUILD)).thenReturn(List.of(queuedSlot(1L, 1, 1)));
    when(queuePort.append(any())).thenReturn(queuedSlot(2L, 2, 1));

    ProposeGameResult result = propose();

    assertThat(result.outcome()).isEqualTo(Outcome.PROPOSED);
    assertThat(result.position()).isEqualTo(2);
    assertThat(result.coinsSpent()).isEqualTo(1);
    assertThat(result.newBalance()).isEqualTo(4);
    verify(rotationPort, never()).bootstrap(anyLong(), anyLong(), any());
    verify(ledgerPort).append(any(), any());
  }

  private ProposeGameResult propose() {
    return service.propose(new ProposeGameRequest(GUILD, MEMBER, GAME, INTERACTION));
  }

  private static QueueSlot queuedSlot(long id, int position, int coins) {
    return new QueueSlot(
        id,
        GUILD,
        MEMBER,
        GAME,
        GameIdentity.of(GAME),
        UUID.randomUUID(),
        position,
        QueueStatus.QUEUED,
        coins,
        0,
        null);
  }

  private static QueueSlot playedSlot(long id, int week, int coins) {
    return new QueueSlot(
        id,
        GUILD,
        MEMBER,
        GAME,
        GameIdentity.of(GAME),
        UUID.randomUUID(),
        null,
        QueueStatus.PLAYED,
        coins,
        0,
        week);
  }
}
