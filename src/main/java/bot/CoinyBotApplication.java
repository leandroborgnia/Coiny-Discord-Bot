package bot;

import bot.infrastructure.discord.DiscordProperties;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DiscordProperties.class)
public class CoinyBotApplication {

  public static void main(String[] args) {
    // Run in UTC: store Instants consistently and avoid sending a legacy host TimeZone id (e.g.
    // "America/Buenos_Aires") that Postgres rejects on connect.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(CoinyBotApplication.class, args);
  }
}
