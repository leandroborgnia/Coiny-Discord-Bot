package bot.infrastructure.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
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
    JDA jda =
        JDABuilder.createLight(properties.token())
            .addEventListeners(router, registrar)
            .setActivity(net.dv8tion.jda.api.entities.Activity.playing("/ping"))
            .build();
    jda.awaitReady();
    return jda;
  }
}
