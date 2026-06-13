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
      DiscordProperties properties,
      InteractionRouter router,
      SlashCommandRegistrar registrar,
      ButtonInteractionRouter buttonRouter)
      throws InterruptedException {
    if (properties.token() == null || properties.token().isBlank()) {
      throw new IllegalStateException(
          "DISCORD_TOKEN must be provided (set it in the environment; never commit it)");
    }
    // GUILD_PRESENCES + GUILD_MEMBERS are PRIVILEGED intents — both must be enabled for this bot in
    // the Discord Developer Portal (Bot → Privileged Gateway Intents). GUILD_PRESENCES is needed to
    // read a member's live Rich Presence (FR-026); GUILD_MEMBERS lets us fetch a single member on
    // demand. GUILD_VOICE_STATES (non-privileged) is added for feature 005 so voice connections are
    // observable. We retain members connected to voice (MemberCachePolicy.VOICE) WITH their
    // activities (CacheFlag.ACTIVITY) and voice states (CacheFlag.VOICE_STATE), so the
    // participation
    // sweep can read who is in a designated voice channel and what they are playing straight from
    // the in-memory cache (no REST) — bounded by the small set currently in voice. NOTE:
    // createLight disables ALL cache flags, so both ACTIVITY and VOICE_STATE must be re-enabled
    // explicitly; without VOICE_STATE, VoiceChannel.getMembers() is empty and nobody ever
    // qualifies.
    // PresenceReader still does its on-demand retrieveMembersByIds(true, id) at propose time
    // (Complexity Tracking; Principle V).
    JDA jda =
        JDABuilder.createLight(
                properties.token(),
                GatewayIntent.GUILD_PRESENCES,
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.GUILD_VOICE_STATES)
            .enableCache(CacheFlag.ACTIVITY, CacheFlag.VOICE_STATE)
            .setMemberCachePolicy(MemberCachePolicy.VOICE)
            .setChunkingFilter(ChunkingFilter.NONE)
            .addEventListeners(router, registrar, buttonRouter)
            .setActivity(Activity.playing("/ping"))
            .build();
    jda.awaitReady();
    return jda;
  }
}
