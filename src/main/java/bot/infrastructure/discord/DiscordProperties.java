package bot.infrastructure.discord;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Discord configuration bound from {@code discord.*}. The token comes from an environment variable
 * and is never committed. No server (guild) id is configured — the bot operates in every server it
 * is a member of (see {@link SlashCommandRegistrar}).
 */
@ConfigurationProperties(prefix = "discord")
public record DiscordProperties(boolean enabled, String token) {}
