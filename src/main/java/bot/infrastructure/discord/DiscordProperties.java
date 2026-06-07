package bot.infrastructure.discord;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Discord configuration bound from {@code discord.*}. Secrets come from environment variables; no
 * value is ever committed.
 */
@ConfigurationProperties(prefix = "discord")
public record DiscordProperties(boolean enabled, String token, String guildId) {}
