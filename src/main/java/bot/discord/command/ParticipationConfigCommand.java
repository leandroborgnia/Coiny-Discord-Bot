package bot.discord.command;

import bot.application.participation.ConfigureParticipationRequest;
import bot.application.participation.ConfigureParticipationRequest.Op;
import bot.application.participation.ConfigureParticipationService;
import bot.application.participation.ParticipationConfigResult;
import bot.domain.DomainException;
import java.util.Set;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /participation-config} handler (moderator-gated) with {@code channel-add}, {@code
 * channel-reset}, {@code rate}, and {@code free-proposal} subcommands. Defers first, parses the
 * interaction, delegates to {@link ConfigureParticipationService} (which performs the authoritative
 * configured-role check), and renders the outcome. Earning itself has no command — it is the
 * background sweep.
 */
@Component
public class ParticipationConfigCommand implements SlashCommandHandler {

  private final ConfigureParticipationService service;
  private final CoinMessages messages;

  public ParticipationConfigCommand(ConfigureParticipationService service, CoinMessages messages) {
    this.service = service;
    this.messages = messages;
  }

  @Override
  public String name() {
    return "participation-config";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(name(), "Configure participation earning (moderator action).")
        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
        .setGuildOnly(true)
        .addSubcommands(
            new SubcommandData("channel-add", "Designate a voice channel for participation earning")
                .addOptions(
                    new OptionData(
                            OptionType.CHANNEL, "channel", "Voice channel to designate", true)
                        .setChannelTypes(ChannelType.VOICE, ChannelType.STAGE)),
            new SubcommandData(
                "channel-reset", "Clear all designated participation voice channels"),
            new SubcommandData("rate", "Set the participation earning rate")
                .addOptions(
                    new OptionData(
                            OptionType.INTEGER,
                            "minutes-per-drop",
                            "Qualifying minutes that earn one drop",
                            true)
                        .setMinValue(1),
                    new OptionData(
                            OptionType.INTEGER, "coins-per-drop", "Coins earned per drop", true)
                        .setMinValue(1)),
            new SubcommandData(
                    "free-proposal",
                    "Waive the propose cost when there's no game and the queue is empty")
                .addOptions(
                    new OptionData(
                        OptionType.BOOLEAN,
                        "enabled",
                        "Whether the first proposal is free",
                        true)));
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    Set<Long> roleIds =
        event.getMember().getRoles().stream().map(Role::getIdLong).collect(Collectors.toSet());
    boolean admin = event.getMember().hasPermission(Permission.ADMINISTRATOR);
    long guildId = event.getGuild().getIdLong();
    long actor = event.getUser().getIdLong();
    String sub = event.getSubcommandName();

    ConfigureParticipationRequest request =
        switch (sub) {
          case "channel-add" ->
              new ConfigureParticipationRequest(
                  guildId,
                  actor,
                  roleIds,
                  admin,
                  Op.CHANNEL_ADD,
                  event.getOption("channel").getAsChannel().getIdLong(),
                  null,
                  null,
                  null);
          case "channel-reset" ->
              new ConfigureParticipationRequest(
                  guildId, actor, roleIds, admin, Op.CHANNEL_RESET, null, null, null, null);
          case "rate" ->
              new ConfigureParticipationRequest(
                  guildId,
                  actor,
                  roleIds,
                  admin,
                  Op.RATE,
                  null,
                  event.getOption("minutes-per-drop").getAsInt(),
                  event.getOption("coins-per-drop").getAsInt(),
                  null);
          case "free-proposal" ->
              new ConfigureParticipationRequest(
                  guildId,
                  actor,
                  roleIds,
                  admin,
                  Op.FREE_PROPOSAL,
                  null,
                  null,
                  null,
                  event.getOption("enabled").getAsBoolean());
          default -> null;
        };

    if (request == null) {
      event.getHook().editOriginal(messages.get("coin.reply.config.need-arg")).queue();
      return;
    }

    try {
      ParticipationConfigResult result = service.configure(request);
      event.getHook().editOriginal(render(sub, result, request)).queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }

  private String render(
      String sub, ParticipationConfigResult result, ConfigureParticipationRequest request) {
    return switch (sub) {
      case "channel-add" ->
          messages.get(
              "participation.reply.channel-added",
              request.channelId(),
              result.designatedChannelCount());
      case "channel-reset" -> messages.get("participation.reply.channel-reset");
      case "rate" ->
          messages.get(
              "participation.reply.rate-set", result.coinsPerDrop(), result.minutesPerDrop());
      case "free-proposal" ->
          messages.get(
              result.freeFirstProposal()
                  ? "participation.reply.free-proposal-set.on"
                  : "participation.reply.free-proposal-set.off");
      default -> messages.get("coin.reply.config.need-arg");
    };
  }
}
