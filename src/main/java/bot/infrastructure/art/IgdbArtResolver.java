package bot.infrastructure.art;

import bot.domain.queue.ArtResolverPort;
import bot.domain.queue.GameIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves a cover image from IGDB (FR-027) via the JDK {@link HttpClient} — no new transport
 * dependency. Authenticates with Twitch OAuth client-credentials (bearer token cached until
 * expiry), then queries {@code /games} by name for the cover image id and builds the CDN url.
 * Best-effort: any failure returns empty. **Disabled (a no-op returning empty) when credentials are
 * blank**, so {@code ./mvnw verify} and credential-less dev runs work and the art chain falls to
 * name-only.
 */
@Component
public class IgdbArtResolver implements ArtResolverPort {

  private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
  private static final String COVER_CDN = "https://images.igdb.com/igdb/image/upload/t_cover_big/";

  private final boolean enabled;
  private final String baseUrl;
  private final String clientId;
  private final String clientSecret;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  private volatile String token;
  private volatile Instant tokenExpiry = Instant.EPOCH;

  @Autowired
  public IgdbArtResolver(
      @Value("${queue.art.igdb.enabled:true}") boolean enabled,
      @Value("${queue.art.igdb.base-url:https://api.igdb.com/v4}") String baseUrl,
      @Value("${queue.art.igdb.client-id:}") String clientId,
      @Value("${queue.art.igdb.client-secret:}") String clientSecret) {
    this(enabled, baseUrl, clientId, clientSecret, HttpClient.newHttpClient());
  }

  IgdbArtResolver(
      boolean enabled, String baseUrl, String clientId, String clientSecret, HttpClient http) {
    this.enabled = enabled && notBlank(clientId) && notBlank(clientSecret);
    this.baseUrl = baseUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.http = http;
  }

  @Override
  public Optional<String> resolveCover(GameIdentity identity, String name) {
    if (!enabled) {
      return Optional.empty();
    }
    try {
      String bearer = ensureToken();
      // Fetch several candidates with their category so we can prefer the main game over DLC.
      String body =
          "search \"" + name.replace("\"", "") + "\"; fields cover.image_id, category; limit 10;";
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/games"))
              .header("Client-ID", clientId)
              .header("Authorization", "Bearer " + bearer)
              .header("Accept", "application/json")
              .POST(BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return Optional.empty();
      }
      JsonNode games = mapper.readTree(response.body());
      if (!games.isArray() || games.isEmpty()) {
        return Optional.empty();
      }
      String imageId = pickMainGameCover(games);
      return imageId == null ? Optional.empty() : Optional.of(COVER_CDN + imageId + ".jpg");
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Prefer the cover of the main game (IGDB {@code category == 0}) over DLC/expansions/bundles;
   * fall back to the first search result that has a cover when no main-game entry does. Search
   * relevance order is preserved within each tier.
   */
  private static String pickMainGameCover(JsonNode games) {
    String fallback = null;
    for (JsonNode game : games) {
      String imageId = game.path("cover").path("image_id").asText("");
      if (imageId.isBlank()) {
        continue;
      }
      if (game.path("category").asInt(-1) == 0) {
        return imageId; // main_game
      }
      if (fallback == null) {
        fallback = imageId;
      }
    }
    return fallback;
  }

  private synchronized String ensureToken() throws Exception {
    if (token != null && Instant.now().isBefore(tokenExpiry)) {
      return token;
    }
    String url =
        TWITCH_TOKEN_URL
            + "?client_id="
            + clientId
            + "&client_secret="
            + clientSecret
            + "&grant_type=client_credentials";
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).POST(BodyPublishers.noBody()).build(),
            BodyHandlers.ofString());
    JsonNode json = mapper.readTree(response.body());
    token = json.path("access_token").asText();
    long expiresIn = json.path("expires_in").asLong(3600);
    tokenExpiry = Instant.now().plusSeconds(Math.max(0, expiresIn - 60));
    return token;
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
