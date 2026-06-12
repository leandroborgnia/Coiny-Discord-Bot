package bot.application.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bot.application.queue.WithdrawGameResult.Outcome;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.MovementRecord;
import bot.domain.queue.CapturedGame;
import bot.domain.queue.GameIdentity;
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
class WithdrawGameServiceTest {

  private static final long GUILD = 100L;
  private static final long MEMBER = 9L;
  private static final long INTERACTION = 777L;
  private static final CapturedGame GAME = CapturedGame.ofName("Celeste");

  @Mock private QueuePort queuePort;
  @Mock private CoinLedgerPort ledgerPort;

  private WithdrawGameService service;

  @BeforeEach
  void setUp() {
    service = new WithdrawGameService(queuePort, ledgerPort);
  }

  @Test
  void withdrawRefundsExactlyTheCoinsSpent() {
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.of(slot(5L, 3)));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(2);

    WithdrawGameResult result =
        service.withdraw(new WithdrawGameRequest(GUILD, MEMBER, INTERACTION));

    assertThat(result.outcome()).isEqualTo(Outcome.WITHDRAWN);
    assertThat(result.refunded()).isEqualTo(3);
    assertThat(result.newBalance()).isEqualTo(5);
    verify(queuePort).withdraw(5L);
    verify(queuePort).shiftUp(GUILD);
    verify(ledgerPort).append(any(), any());
  }

  @Test
  void noQueuedGameReturnsNoQueuedWithoutRefund() {
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(queuePort.ownQueued(GUILD, MEMBER)).thenReturn(Optional.empty());
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(7);

    WithdrawGameResult result =
        service.withdraw(new WithdrawGameRequest(GUILD, MEMBER, INTERACTION));

    assertThat(result.outcome()).isEqualTo(Outcome.NO_QUEUED);
    verify(queuePort, never()).withdraw(anyLong());
    verify(ledgerPort, never()).append(any(), any());
  }

  @Test
  void duplicateWithdrawReturnsDuplicateWithoutRefunding() {
    when(ledgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.of(refundMovement(3)));
    when(ledgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);

    WithdrawGameResult result =
        service.withdraw(new WithdrawGameRequest(GUILD, MEMBER, INTERACTION));

    assertThat(result.outcome()).isEqualTo(Outcome.DUPLICATE);
    assertThat(result.refunded()).isEqualTo(3);
    verify(queuePort, never()).withdraw(anyLong());
    verify(ledgerPort, never()).append(any(), any());
  }

  private static QueueSlot slot(long id, int coinsSpent) {
    return new QueueSlot(
        id,
        GUILD,
        MEMBER,
        GAME,
        GameIdentity.of(GAME),
        UUID.randomUUID(),
        1,
        QueueStatus.QUEUED,
        coinsSpent,
        0,
        null);
  }

  private static MovementRecord refundMovement(int amount) {
    return new MovementRecord(
        1L,
        GUILD,
        MEMBER,
        MEMBER,
        AdjustmentType.QUEUE_REFUND,
        amount,
        0,
        0,
        null,
        INTERACTION,
        Instant.now());
  }
}
