package bot.application.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bot.application.queue.ConfigureQueueRequest.ChannelOp;
import bot.domain.queue.GuildQueueConfig;
import bot.domain.queue.NotAuthorizedException;
import bot.domain.queue.QueueConfigPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfigureQueueServiceTest {

  private static final long GUILD = 100L;

  @Mock private QueueConfigPort configPort;

  private ConfigureQueueService service;

  @BeforeEach
  void setUp() {
    service = new ConfigureQueueService(configPort);
  }

  @Test
  void managerUpdatesCosts() {
    when(configPort.get(GUILD)).thenReturn(new GuildQueueConfig(GUILD, 5, 3, null, null, null));

    QueueConfigResult result =
        service.configure(new ConfigureQueueRequest(GUILD, true, 5, 3, null));

    assertThat(result.proposeCost()).isEqualTo(5);
    assertThat(result.bumpCost()).isEqualTo(3);
    verify(configPort).upsertCosts(GUILD, 5, 3);
  }

  @Test
  void nonManagerIsRejectedAndNothingChanges() {
    assertThatThrownBy(() -> service.configure(new ConfigureQueueRequest(GUILD, false, 5, 3, null)))
        .isInstanceOf(NotAuthorizedException.class);
    verifyNoInteractions(configPort);
  }

  @Test
  void managerSetsAnnouncementChannel() {
    when(configPort.get(GUILD)).thenReturn(new GuildQueueConfig(GUILD, 1, 1, 777L, null, null));

    QueueConfigResult result =
        service.configure(new ConfigureQueueRequest(GUILD, true, null, null, ChannelOp.set(777L)));

    assertThat(result.announcementChannelId()).isEqualTo(777L);
    verify(configPort).setAnnouncementChannel(GUILD, 777L);
  }

  @Test
  void managerClearsAnnouncementChannel() {
    when(configPort.get(GUILD)).thenReturn(GuildQueueConfig.defaults(GUILD));

    QueueConfigResult result =
        service.configure(new ConfigureQueueRequest(GUILD, true, null, null, ChannelOp.off()));

    assertThat(result.announcementChannelId()).isNull();
    verify(configPort).setAnnouncementChannel(GUILD, null);
  }
}
