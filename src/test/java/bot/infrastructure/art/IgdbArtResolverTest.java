package bot.infrastructure.art;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bot.domain.queue.GameIdentity;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the IGDB resolver against a stubbed {@link HttpClient} — no network, no secrets.
 */
class IgdbArtResolverTest {

  private static final GameIdentity ID = new GameIdentity("name:hades");
  private static final String BASE = "https://api.igdb.com/v4";

  @Test
  void disabledWhenCredentialsAreBlank() throws Exception {
    HttpClient http = mock(HttpClient.class);
    IgdbArtResolver resolver = new IgdbArtResolver(true, BASE, "", "", http);

    assertThat(resolver.resolveCover(ID, "Hades")).isEmpty();
    verifyNoInteractions(http);
  }

  @Test
  void resolvesCoverFromIgdb() throws Exception {
    HttpClient http = mock(HttpClient.class);
    HttpResponse<String> tokenResponse = response("{\"access_token\":\"tok\",\"expires_in\":3600}");
    HttpResponse<String> gamesResponse = response("[{\"cover\":{\"image_id\":\"co1234\"}}]");
    when(http.<String>send(any(), any())).thenReturn(tokenResponse).thenReturn(gamesResponse);
    IgdbArtResolver resolver = new IgdbArtResolver(true, BASE, "id", "secret", http);

    Optional<String> cover = resolver.resolveCover(ID, "Hades");

    assertThat(cover).contains("https://images.igdb.com/igdb/image/upload/t_cover_big/co1234.jpg");
  }

  @Test
  void cachesTheBearerTokenAcrossCalls() throws Exception {
    AtomicInteger tokenCalls = new AtomicInteger();
    HttpClient http = mock(HttpClient.class);
    when(http.<String>send(any(), any()))
        .thenAnswer(
            invocation -> {
              HttpRequest request = invocation.getArgument(0);
              if (request.uri().toString().contains("id.twitch.tv")) {
                tokenCalls.incrementAndGet();
                return response("{\"access_token\":\"tok\",\"expires_in\":3600}");
              }
              return response("[{\"cover\":{\"image_id\":\"co1\"}}]");
            });
    IgdbArtResolver resolver = new IgdbArtResolver(true, BASE, "id", "secret", http);

    resolver.resolveCover(ID, "Hades");
    resolver.resolveCover(ID, "Celeste");

    assertThat(tokenCalls.get()).isEqualTo(1); // token fetched once, then cached
  }

  @Test
  void networkFailureReturnsEmpty() throws Exception {
    HttpClient http = mock(HttpClient.class);
    when(http.<String>send(any(), any())).thenThrow(new IOException("igdb unreachable"));
    IgdbArtResolver resolver = new IgdbArtResolver(true, BASE, "id", "secret", http);

    assertThat(resolver.resolveCover(ID, "Hades")).isEmpty();
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse<String> response(String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(body);
    return response;
  }
}
