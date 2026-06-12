package bot.infrastructure.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link JDA} gateway connection from the environment-supplied token and wires the
 * interaction router and command registrar as listeners. Disabled when {@code discord.enabled} is
 * false (e.g. in tests), so no Discord connection is attempted.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class JdaConfig {

  @Bean(destroyMethod = "shutdown")
  public JDA jda(
      DiscordProperties properties, InteractionRouter router, SlashCommandRegistrar registrar)
      throws InterruptedException {
    if (properties.token() == null || properties.token().isBlank()) {
      throw new IllegalStateException(
          "DISCORD_TOKEN must be provided (set it in the environment; never commit it)");
    }
    // GUILD_PRESENCES + GUILD_MEMBERS are PRIVILEGED intents — both must be enabled for this bot in
    // the Discord Developer Portal (Bot → Privileged Gateway Intents). GUILD_PRESENCES is needed to
    // read a member's live Rich Presence when they propose (FR-026); GUILD_MEMBERS lets us fetch a
    // single member on demand. We retain NOTHING: MemberCachePolicy.NONE + ChunkingFilter.NONE keep
    // memory flat at any scale, and PresenceReader reads the activity via an on-demand
    // retrieveMembersByIds(true, id) only at propose time (Complexity Tracking; Principle V).
    // CacheFlag.ACTIVITY enables activity data on the members we explicitly fetch.
    JDA jda =
        JDABuilder.createLight(
                properties.token(), GatewayIntent.GUILD_PRESENCES, GatewayIntent.GUILD_MEMBERS)
            .enableCache(CacheFlag.ACTIVITY)
            .setMemberCachePolicy(MemberCachePolicy.NONE)
            .setChunkingFilter(ChunkingFilter.NONE)
            .addEventListeners(router, registrar)
            .setActivity(Activity.playing("/ping"))
            .build();
    jda.awaitReady();
    return jda;
  }
}
