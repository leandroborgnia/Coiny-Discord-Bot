package bot.application.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bot.application.queue.ToggleUpvoteResult.Outcome;
import bot.domain.queue.AnnouncementView;
import bot.domain.queue.GuildQueueConfig;
import bot.domain.queue.QueueConfigPort;
import bot.domain.queue.QueuePort;
import bot.domain.queue.UpvotePort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpvoteServiceTest {

  private static final long GUILD = 100L;
  private static final long MEMBER = 9L;
  private static final long SLOT = 42L;
  private static final long INTERACTION = 7L;
  private static final UUID INSTANCE = UUID.randomUUID();

  @Mock private QueuePort queuePort;
  @Mock private UpvotePort upvotePort;
  @Mock private QueueConfigPort configPort;
  @Mock private AnnouncementAssembler announcementAssembler;

  private UpvoteService service;

  @BeforeEach
  void setUp() {
    service = new UpvoteService(queuePort, upvotePort, configPort, announcementAssembler);
  }

  @Test
  void togglesAndReportsNewCountWithoutLiveSurfaceWhenNoChannel() {
    when(queuePort.currentInstance(SLOT)).thenReturn(Optional.of(INSTANCE));
    when(upvotePort.toggle(SLOT, MEMBER, INSTANCE)).thenReturn(true);
    when(upvotePort.count(SLOT, INSTANCE)).thenReturn(1);
    when(configPort.get(GUILD)).thenReturn(GuildQueueConfig.defaults(GUILD));

    ToggleUpvoteResult result = service.toggle(request(INSTANCE));

    assertThat(result.outcome()).isEqualTo(Outcome.TOGGLED);
    assertThat(result.changed()).isTrue();
    assertThat(result.newCount()).isEqualTo(1);
    assertThat(result.liveSurface()).isEmpty();
  }

  @Test
  void returnsLiveSurfaceWhenChannelConfiguredAndVoteChanged() {
    when(queuePort.currentInstance(SLOT)).thenReturn(Optional.of(INSTANCE));
    when(upvotePort.toggle(SLOT, MEMBER, INSTANCE)).thenReturn(true);
    when(upvotePort.count(SLOT, INSTANCE)).thenReturn(3);
    when(configPort.get(GUILD)).thenReturn(new GuildQueueConfig(GUILD, 1, 1, 555L, 555L, 999L));
    when(announcementAssembler.assemble(GUILD))
        .thenReturn(Optional.of(new AnnouncementView(GUILD, "Hades", 1L, null, List.of())));

    ToggleUpvoteResult result = service.toggle(request(INSTANCE));

    assertThat(result.liveSurface()).isPresent();
    assertThat(result.liveSurface().get().messageId()).isEqualTo(999L);
    assertThat(result.announcementView()).isPresent();
  }

  @Test
  void staleButtonWritesNothing() {
    when(queuePort.currentInstance(SLOT))
        .thenReturn(Optional.of(UUID.randomUUID())); // game replaced

    ToggleUpvoteResult result = service.toggle(request(INSTANCE));

    assertThat(result.outcome()).isEqualTo(Outcome.STALE);
    assertThat(result.changed()).isFalse();
    verify(upvotePort, never()).toggle(anyLong(), anyLong(), any());
  }

  @Test
  void missingSlotReturnsNoSlot() {
    when(queuePort.currentInstance(SLOT)).thenReturn(Optional.empty());

    assertThat(service.toggle(request(INSTANCE)).outcome()).isEqualTo(Outcome.NO_SLOT);
    verify(upvotePort, never()).toggle(anyLong(), anyLong(), any());
  }

  @Test
  void unchangedToggleHasNoLiveSurface() {
    when(queuePort.currentInstance(SLOT)).thenReturn(Optional.of(INSTANCE));
    when(upvotePort.toggle(SLOT, MEMBER, INSTANCE)).thenReturn(false); // a lost-race no-op
    when(upvotePort.count(SLOT, INSTANCE)).thenReturn(1);

    ToggleUpvoteResult result = service.toggle(request(INSTANCE));

    assertThat(result.changed()).isFalse();
    assertThat(result.liveSurface()).isEmpty();
    verify(announcementAssembler, never()).assemble(anyLong());
  }

  private static ToggleUpvoteRequest request(UUID instance) {
    return new ToggleUpvoteRequest(GUILD, MEMBER, SLOT, instance, INTERACTION);
  }
}
