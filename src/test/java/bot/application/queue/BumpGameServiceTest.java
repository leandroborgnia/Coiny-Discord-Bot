package bot.application.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bot.application.queue.BumpGameResult.Outcome;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.MovementRecord;
import bot.domain.queue.CapturedGame;
import bot.domain.queue.GameIdentity;
import bot.domain.queue.GuildQueueConfig;
import bot.domain.queue.InsufficientCoinsException;
import bot.domain.queue.QueueConfigPort;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.QueueStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BumpGameServiceTest {

  private static final long GUILD = 100L;
  private static final long MEMBER = 9L;
  private static final long INTERACTION = 555L;
  private static final CapturedGame GAME = CapturedGame.ofName("Hades");

  @Mock private QueuePort queuePort;
  @Mock private QueueConfigPort configPort;
  @Mock private CoinLedgerPort ledgerPort;

  private BumpGameService service;

  @BeforeEach
  void setUp() {
    service = new BumpGameService(queuePort, configPort, ledgerPort);
  }

  @Test
  void bumpsOwnNonTopSlotBySingleSwap() {
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.of(slot(5L, 3)));
    when(configPort.get(GUILD)).thenReturn(GuildQueueConfig.defaults(GUILD));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);

    BumpGameResult result = bump();

    assertThat(result.outcome()).isEqualTo(Outcome.BUMPED);
    assertThat(result.newPosition()).isEqualTo(2);
    assertThat(result.coinsSpent()).isEqualTo(1);
    assertThat(result.newBalance()).isEqualTo(4);
    verify(queuePort).bumpSwap(GUILD, 5L, 3);
    verify(queuePort).addCoinsSpent(5L, 1);
    verify(ledgerPort).append(any(), any());
  }

  @Test
  void bumpingTheTopChangesNothing() {
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.of(slot(5L, 1)));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);

    assertThat(bump().outcome()).isEqualTo(Outcome.AT_TOP);
    verify(queuePort, never()).bumpSwap(anyLong(), anyLong(), anyInt());
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void noQueuedGameReturnsNoQueued() {
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.empty());
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);

    assertThat(bump().outcome()).isEqualTo(Outcome.NO_QUEUED);
    verify(queuePort, never()).bumpSwap(anyLong(), anyLong(), anyInt());
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void unaffordableBumpThrowsAndChangesNothing() {
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.of(slot(5L, 3)));
    when(configPort.get(GUILD)).thenReturn(GuildQueueConfig.defaults(GUILD));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(0);

    assertThatThrownBy(this::bump).isInstanceOf(InsufficientCoinsException.class);
    verify(queuePort, never()).bumpSwap(anyLong(), anyLong(), anyInt());
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void duplicateBumpReturnsDuplicateWithoutReordering() {
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.of(bumpMovement()));
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.of(slot(5L, 2)));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(4);

    BumpGameResult result = bump();

    assertThat(result.outcome()).isEqualTo(Outcome.DUPLICATE);
    verify(queuePort, never()).bumpSwap(anyLong(), anyLong(), anyInt());
    verify(ledgerPort, never()).append(any(), any());
  }

  private BumpGameResult bump() {
    return service.bump(new BumpGameRequest(GUILD, MEMBER, INTERACTION));
  }

  private static QueueSlot slot(long id, int position) {
    return new QueueSlot(
        id,
        GUILD,
        MEMBER,
        GAME,
        GameIdentity.of(GAME),
        UUID.randomUUID(),
        position,
        QueueStatus.QUEUED,
        1,
        0,
        null);
  }

  private static MovementRecord bumpMovement() {
    return new MovementRecord(
        1L,
        GUILD,
        MEMBER,
        MEMBER,
        AdjustmentType.QUEUE_BUMP,
        1,
        0,
        0,
        null,
        INTERACTION,
        Instant.now());
  }
}
