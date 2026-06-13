package bot.discord.command;

import bot.application.skipjar.ConfigureRequest;
import bot.application.skipjar.ConfigureRequest.Op;
import bot.application.skipjar.ConfigureSkipJarService;
import bot.application.skipjar.SkipJarConfigResult;
import bot.domain.DomainException;
import java.util.Set;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /skip-config} handler (moderator-gated) with {@code floor}, {@code dwell}, and {@code
 * gate} subcommands. Defers first, parses the interaction, delegates to {@link
 * ConfigureSkipJarService} (which performs the authoritative configured-role check), and renders
 * the re-read effective configuration. Dwell is entered in hours and stored as seconds.
 */
@Component
public class SkipConfigCommand implements SlashCommandHandler {

  private final ConfigureSkipJarService service;
  private final CoinMessages messages;

  public SkipConfigCommand(ConfigureSkipJarService service, CoinMessages messages) {
    this.service = service;
    this.messages = messages;
  }

  @Override
  public String name() {
    return "skip-config";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(name(), "Configure the skip jar (moderator action).")
        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
        .setGuildOnly(true)
        .addSubcommands(
            new SubcommandData("floor", "Set the minimum contributions required to skip a game")
                .addOptions(
                    new OptionData(OptionType.INTEGER, "value", "Minimum contributions", true)
                        .setMinValue(1)),
            new SubcommandData("dwell", "Set how long a game must be current before its jar opens")
                .addOptions(
                    new OptionData(OptionType.NUMBER, "hours", "Dwell time in hours", true)
                        .setMinValue(0.0001)),
            new SubcommandData("gate", "Restrict contributions to members who earned from the game")
                .addOptions(
                    new OptionData(
                        OptionType.BOOLEAN,
                        "enabled",
                        "Whether only earners may contribute",
                        true)));
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    Set<Long> roleIds =
        event.getMember().getRoles().stream().map(Role::getIdLong).collect(Collectors.toSet());
    boolean admin = event.getMember().hasPermission(Permission.ADMINISTRATOR);
    long guildId = event.getGuild().getIdLong();
    String sub = event.getSubcommandName();

    ConfigureRequest request =
        switch (sub) {
          case "floor" ->
              new ConfigureRequest(
                  guildId,
                  Op.FLOOR,
                  roleIds,
                  admin,
                  event.getOption("value").getAsInt(),
                  0L,
                  false);
          case "dwell" ->
              new ConfigureRequest(
                  guildId,
                  Op.DWELL,
                  roleIds,
                  admin,
                  0,
                  Math.round(event.getOption("hours").getAsDouble() * 3600),
                  false);
          case "gate" ->
              new ConfigureRequest(
                  guildId,
                  Op.GATE,
                  roleIds,
                  admin,
                  0,
                  0L,
                  event.getOption("enabled").getAsBoolean());
          default -> null;
        };

    if (request == null) {
      event.getHook().editOriginal(messages.get("coin.reply.config.need-arg")).queue();
      return;
    }

    try {
      SkipJarConfigResult result = service.configure(request);
      event.getHook().editOriginal(render(result)).queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }

  private String render(SkipJarConfigResult result) {
    double hours = result.dwellSeconds() / 3600.0;
    String gate = result.gateOn() ? "on" : "off";
    return messages.get("skip.reply.config.updated", result.thresholdFloor(), hours, gate);
  }
}
