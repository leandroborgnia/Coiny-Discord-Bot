package bot.application.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import bot.domain.queue.CapturedGame;
import bot.domain.queue.CooldownPort;
import bot.domain.queue.GameIdentity;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.QueueStatus;
import bot.domain.queue.QueueView;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import bot.domain.queue.UpvotePort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ViewQueueServiceTest {

  private static final long GUILD = 100L;
  private static final long VIEWER = 9L;
  private static final CapturedGame GAME = CapturedGame.ofName("Hades");

  @Mock private QueuePort queuePort;
  @Mock private RotationStatePort rotationPort;
  @Mock private CooldownPort cooldownPort;
  @Mock private UpvotePort upvotePort;
  @Mock private ArtResolutionChain artChain;

  private ViewQueueService service;

  @BeforeEach
  void setUp() {
    service = new ViewQueueService(queuePort, rotationPort, cooldownPort, upvotePort, artChain);
  }

  @Test
  void showsCurrentTopFiveAndOwnEntryBeyondTopFive() {
    List<QueueSlot> queued =
        IntStream.rangeClosed(1, 7).mapToObj(p -> queuedSlot(p, 1000 + p, p)).toList();
    QueueSlot ownSlot = queued.get(5); // position 6, beyond the top five
    when(queuePort.queued(GUILD)).thenReturn(queued);
    when(rotationPort.get(GUILD))
        .thenReturn(new RotationState(GUILD, 99L, 1, java.time.Instant.now()));
    when(queuePort.findSlot(99L)).thenReturn(Optional.of(playedSlot(99L, 5L, 1)));
    when(queuePort.ownQueued(GUILD, VIEWER)).thenReturn(Optional.of(ownSlot));
    when(cooldownPort.gamesRemaining(GUILD, VIEWER)).thenReturn(0);
    when(upvotePort.count(anyLong(), any())).thenReturn(0);
    when(artChain.coverFor(any(), any(), any())).thenReturn(Optional.empty());

    QueueView view = service.view(new ViewQueueRequest(GUILD, VIEWER));

    assertThat(view.currentGame()).isPresent();
    assertThat(view.upNext()).hasSize(5);
    assertThat(view.upNext()).extracting(QueueView.Entry::position).containsExactly(1, 2, 3, 4, 5);
    assertThat(view.ownEntry()).isPresent();
    assertThat(view.ownEntry().get().position()).isEqualTo(6);
    assertThat(view.ownEntryShownInUpNext()).isFalse();
    assertThat(view.eligibleToPropose()).isFalse(); // holds a queued game
  }

  @Test
  void ownEntryWithinTopFiveIsMarkedInPlace() {
    List<QueueSlot> queued =
        List.of(queuedSlot(1, 1001, 1), queuedSlot(2, VIEWER, 2), queuedSlot(3, 1003, 3));
    when(queuePort.queued(GUILD)).thenReturn(queued);
    when(rotationPort.get(GUILD)).thenReturn(new RotationState(GUILD, null, 0, null));
    when(queuePort.ownQueued(GUILD, VIEWER)).thenReturn(Optional.of(queued.get(1)));
    when(cooldownPort.gamesRemaining(GUILD, VIEWER)).thenReturn(0);
    when(upvotePort.count(anyLong(), any())).thenReturn(0);
    when(artChain.coverFor(any(), any(), any())).thenReturn(Optional.empty());

    QueueView view = service.view(new ViewQueueRequest(GUILD, VIEWER));

    assertThat(view.currentGame()).isEmpty();
    assertThat(view.ownEntryShownInUpNext()).isTrue();
  }

  @Test
  void emptyStateIsEligibleWithNothingShown() {
    when(queuePort.queued(GUILD)).thenReturn(List.of());
    when(rotationPort.get(GUILD)).thenReturn(new RotationState(GUILD, null, 0, null));
    when(queuePort.ownQueued(GUILD, VIEWER)).thenReturn(Optional.empty());
    when(cooldownPort.gamesRemaining(GUILD, VIEWER)).thenReturn(0);

    QueueView view = service.view(new ViewQueueRequest(GUILD, VIEWER));

    assertThat(view.currentGame()).isEmpty();
    assertThat(view.upNext()).isEmpty();
    assertThat(view.ownEntry()).isEmpty();
    assertThat(view.eligibleToPropose()).isTrue();
  }

  @Test
  void departedProposerStillRendersByStoredId() {
    long departed = 424242L;
    when(queuePort.queued(GUILD)).thenReturn(List.of(queuedSlot(1, departed, 1)));
    when(rotationPort.get(GUILD)).thenReturn(new RotationState(GUILD, null, 0, null));
    when(queuePort.ownQueued(GUILD, VIEWER)).thenReturn(Optional.empty());
    when(cooldownPort.gamesRemaining(GUILD, VIEWER)).thenReturn(0);
    when(upvotePort.count(anyLong(), any())).thenReturn(0);
    when(artChain.coverFor(any(), any(), any())).thenReturn(Optional.empty());

    QueueView view = service.view(new ViewQueueRequest(GUILD, VIEWER));

    assertThat(view.upNext().get(0).proposerId()).isEqualTo(departed);
  }

  private static QueueSlot queuedSlot(long id, long member, int position) {
    return new QueueSlot(
        id,
        GUILD,
        member,
        GAME,
        GameIdentity.of(GAME),
        UUID.randomUUID(),
        position,
        QueueStatus.QUEUED,
        1,
        0,
        null);
  }

  private static QueueSlot playedSlot(long id, long member, int week) {
    return new QueueSlot(
        id,
        GUILD,
        member,
        GAME,
        GameIdentity.of(GAME),
        UUID.randomUUID(),
        null,
        QueueStatus.PLAYED,
        1,
        0,
        week);
  }
}
