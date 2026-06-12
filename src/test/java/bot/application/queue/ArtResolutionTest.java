package bot.application.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bot.domain.queue.ArtCachePort;
import bot.domain.queue.ArtEntry;
import bot.domain.queue.ArtResolverPort;
import bot.domain.queue.ArtSource;
import bot.domain.queue.GameIdentity;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArtResolutionTest {

  private static final GameIdentity ID = new GameIdentity("name:hades");

  @Mock private ArtCachePort cachePort;
  @Mock private ArtResolverPort resolverPort;

  private ArtResolutionChain chain;

  @BeforeEach
  void setUp() {
    chain = new ArtResolutionChain(cachePort, resolverPort);
  }

  @Test
  void richPresenceAssetIsUsedDirectlyWithoutCacheOrIgdb() {
    Optional<String> cover = chain.coverFor(ID, "https://rp/large.png", "Hades");

    assertThat(cover).contains("https://rp/large.png");
    verifyNoInteractions(cachePort, resolverPort);
  }

  @Test
  void cacheHitReturnsStoredUrlAndSkipsIgdb() {
    when(cachePort.lookup(ID))
        .thenReturn(Optional.of(new ArtEntry("https://cache.png", ArtSource.IGDB)));

    Optional<String> cover = chain.coverFor(ID, null, "Hades");

    assertThat(cover).contains("https://cache.png");
    verifyNoInteractions(resolverPort);
  }

  @Test
  void cachedMissRendersNameOnlyWithoutIgdb() {
    when(cachePort.lookup(ID)).thenReturn(Optional.of(new ArtEntry(null, ArtSource.NONE)));

    assertThat(chain.coverFor(ID, null, "Hades")).isEmpty();
    verifyNoInteractions(resolverPort);
  }

  @Test
  void cacheMissResolvesViaIgdbAndStoresIt() {
    when(cachePort.lookup(ID)).thenReturn(Optional.empty());
    when(resolverPort.resolveCover(ID, "Hades")).thenReturn(Optional.of("https://igdb.png"));

    Optional<String> cover = chain.coverFor(ID, null, "Hades");

    assertThat(cover).contains("https://igdb.png");
    verify(cachePort).store(ID, "https://igdb.png", ArtSource.IGDB);
  }

  @Test
  void igdbMissIsCachedAsNone() {
    when(cachePort.lookup(ID)).thenReturn(Optional.empty());
    when(resolverPort.resolveCover(ID, "Hades")).thenReturn(Optional.empty());

    assertThat(chain.coverFor(ID, null, "Hades")).isEmpty();
    verify(cachePort).store(ID, null, ArtSource.NONE);
  }

  @Test
  void resolverFailureDegradesToNameOnlyAndNeverThrows() {
    when(cachePort.lookup(ID)).thenReturn(Optional.empty());
    when(resolverPort.resolveCover(ID, "Hades")).thenThrow(new RuntimeException("igdb down"));

    assertThat(chain.coverFor(ID, null, "Hades")).isEmpty();
    verify(cachePort, never())
        .store(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }
}
