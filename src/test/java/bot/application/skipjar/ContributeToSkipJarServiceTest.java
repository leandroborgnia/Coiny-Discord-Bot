package bot.application.skipjar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bot.application.queue.AdvanceRotationService;
import bot.domain.coin.AdjustmentType;
import bot.domain.coin.AppendResult;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.MovementRecord;
import bot.domain.queue.CapturedGame;
import bot.domain.queue.GameIdentity;
import bot.domain.queue.QueuePort;
import bot.domain.queue.QueueSlot;
import bot.domain.queue.QueueStatus;
import bot.domain.queue.RotationState;
import bot.domain.queue.RotationStatePort;
import bot.domain.skipjar.AlreadyContributedException;
import bot.domain.skipjar.EarnerStatsPort;
import bot.domain.skipjar.GuildSkipJarConfig;
import bot.domain.skipjar.JarClosedException;
import bot.domain.skipjar.NoCurrentGameException;
import bot.domain.skipjar.NotEligibleToContributeException;
import bot.domain.skipjar.SkipContributionPort;
import bot.domain.skipjar.SkipJarConfigPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContributeToSkipJarServiceTest {

  private static final long GUILD = 100L;
  private static final long MEMBER = 7L;
  private static final long INTERACTION = 9999L;
  private static final long SLOT = 555L;
  private static final int WEEK = 3;

  @Mock private QueuePort queuePort;
  @Mock private RotationStatePort rotationStatePort;
  @Mock private SkipJarConfigPort skipJarConfigPort;
  @Mock private EarnerStatsPort earnerStatsPort;
  @Mock private SkipContributionPort skipContributionPort;
  @Mock private CoinLedgerPort coinLedgerPort;
  @Mock private AdvanceRotationService advanceRotationService;

  private ContributeToSkipJarService service;

  @BeforeEach
  void setUp() {
    service =
        new ContributeToSkipJarService(
            queuePort,
            rotationStatePort,
            skipJarConfigPort,
            earnerStatsPort,
            skipContributionPort,
            coinLedgerPort,
            advanceRotationService);
  }

  @Test
  void noCurrentGameIsRefused() {
    when(coinLedgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(rotationStatePort.get(GUILD)).thenReturn(new RotationState(GUILD, null, WEEK, null));

    assertThatThrownBy(() -> service.contribute(request(Instant.now())))
        .isInstanceOf(NoCurrentGameException.class);
  }

  @Test
  void contributionWithinDwellIsRefused() {
    Instant now = Instant.now();
    when(coinLedgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(rotationStatePort.get(GUILD)).thenReturn(state(now)); // became current just now
    when(queuePort.findSlot(SLOT)).thenReturn(Optional.of(slot()));
    when(skipJarConfigPort.get(GUILD)).thenReturn(GuildSkipJarConfig.defaults(GUILD)); // 24h dwell

    assertThatThrownBy(() -> service.contribute(request(now)))
        .isInstanceOf(JarClosedException.class);
    verify(coinLedgerPort, never()).lockAccount(anyLong(), anyLong());
  }

  @Test
  void gateOnNonEarnerIsRefused() {
    Instant now = Instant.now();
    when(coinLedgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(rotationStatePort.get(GUILD)).thenReturn(state(now.minus(Duration.ofHours(48))));
    when(queuePort.findSlot(SLOT)).thenReturn(Optional.of(slot()));
    when(skipJarConfigPort.get(GUILD)).thenReturn(GuildSkipJarConfig.defaults(GUILD)); // gate on
    when(earnerStatsPort.isEarner(anyLong(), anyLong(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.contribute(request(now)))
        .isInstanceOf(NotEligibleToContributeException.class);
    verify(coinLedgerPort, never()).lockAccount(anyLong(), anyLong());
  }

  @Test
  void gateOffSkipsTheEarnerCheckAndCharges() {
    Instant now = Instant.now();
    stubHappyPath(now, gateOff());

    ContributeResult result = service.contribute(request(now));

    assertThat(result.charged()).isTrue();
    assertThat(result.skipped()).isFalse();
    verify(earnerStatsPort, never()).isEarner(anyLong(), anyLong(), any());
  }

  @Test
  void alreadyContributedIsRefused() {
    Instant now = Instant.now();
    when(coinLedgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(rotationStatePort.get(GUILD)).thenReturn(state(now.minus(Duration.ofHours(48))));
    when(queuePort.findSlot(SLOT)).thenReturn(Optional.of(slot()));
    when(skipJarConfigPort.get(GUILD)).thenReturn(gateOff());
    when(skipContributionPort.hasContributed(GUILD, WEEK, MEMBER)).thenReturn(true);

    assertThatThrownBy(() -> service.contribute(request(now)))
        .isInstanceOf(AlreadyContributedException.class);
    verify(coinLedgerPort, never()).lockAccount(anyLong(), anyLong());
  }

  @Test
  void locksTheQueueBeforeTheAccount() {
    Instant now = Instant.now();
    stubHappyPath(now, gateOff());

    service.contribute(request(now));

    InOrder order = inOrder(queuePort, coinLedgerPort);
    order.verify(queuePort).lockQueue(GUILD);
    order.verify(coinLedgerPort).lockAccount(GUILD, MEMBER);
  }

  // --- helpers ---

  private void stubHappyPath(Instant now, GuildSkipJarConfig config) {
    when(coinLedgerPort.findByInteractionId(INTERACTION)).thenReturn(Optional.empty());
    when(rotationStatePort.get(GUILD)).thenReturn(state(now.minus(Duration.ofHours(48))));
    when(queuePort.findSlot(SLOT)).thenReturn(Optional.of(slot()));
    when(skipJarConfigPort.get(GUILD)).thenReturn(config);
    when(skipContributionPort.hasContributed(GUILD, WEEK, MEMBER)).thenReturn(false);
    when(coinLedgerPort.currentBalance(GUILD, MEMBER)).thenReturn(5);
    when(coinLedgerPort.append(any(), any())).thenReturn(new AppendResult(movement(), true));
    when(skipContributionPort.count(GUILD, WEEK)).thenReturn(1);
    when(earnerStatsPort.distinctEarnerCount(GUILD, now.minus(Duration.ofHours(48)))).thenReturn(1);
  }

  private static GuildSkipJarConfig gateOff() {
    return new GuildSkipJarConfig(GUILD, 3, Duration.ofHours(24), false);
  }

  private static ContributeRequest request(Instant now) {
    return new ContributeRequest(GUILD, MEMBER, INTERACTION, now);
  }

  private static RotationState state(Instant lastPopAt) {
    return new RotationState(GUILD, SLOT, WEEK, lastPopAt);
  }

  private static QueueSlot slot() {
    CapturedGame game = CapturedGame.ofName("Hades");
    return new QueueSlot(
        SLOT,
        GUILD,
        1L,
        game,
        GameIdentity.of(game),
        UUID.randomUUID(),
        null,
        QueueStatus.PLAYED,
        1,
        0,
        WEEK);
  }

  private static MovementRecord movement() {
    return new MovementRecord(
        1L,
        GUILD,
        MEMBER,
        MEMBER,
        AdjustmentType.SKIP_JAR,
        1,
        0,
        0,
        null,
        INTERACTION,
        Instant.now());
  }
}
