package bot.discord.command;

import bot.application.queue.ConfigureQueueRequest;
import bot.application.queue.ConfigureQueueRequest.ChannelOp;
import bot.application.queue.ConfigureQueueService;
import bot.application.queue.QueueConfigResult;
import bot.domain.DomainException;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /queue-config} handler (Manage Server-gated). Subcommands: {@code costs}
 * (propose/bump), {@code announce} (set the channel), {@code announce-clear}. The Discord {@code
 * DefaultMemberPermissions} filter and the in-service check are both Manage Server, so they never
 * disagree (resolves analysis finding I1).
 */
@Component
public class QueueConfigCommand implements SlashCommandHandler {

  private final ConfigureQueueService configureQueueService;
  private final QueueMessages messages;

  public QueueConfigCommand(ConfigureQueueService configureQueueService, QueueMessages messages) {
    this.configureQueueService = configureQueueService;
    this.messages = messages;
  }

  @Override
  public String name() {
    return "queue-config";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(name(), "Configure the game queue (costs and announcement channel).")
        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
        .setGuildOnly(true)
        .addSubcommands(
            new SubcommandData("costs", "Set the propose and/or bump cost")
                .addOptions(
                    new OptionData(OptionType.INTEGER, "propose", "Coins to propose (≥ 1)", false)
                        .setMinValue(1),
                    new OptionData(OptionType.INTEGER, "bump", "Coins per bump (≥ 1)", false)
                        .setMinValue(1)),
            new SubcommandData("announce", "Set the weekly-rotation announcement channel")
                .addOptions(
                    new OptionData(
                            OptionType.CHANNEL, "channel", "Where to post announcements", true)
                        .setChannelTypes(ChannelType.TEXT)),
            new SubcommandData("announce-clear", "Turn off rotation announcements"));
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    boolean manageServer =
        event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER);
    long guildId = event.getGuild().getIdLong();
    String subcommand = event.getSubcommandName();

    ConfigureQueueRequest request;
    if ("costs".equals(subcommand)) {
      OptionMapping propose = event.getOption("propose");
      OptionMapping bump = event.getOption("bump");
      if (propose == null && bump == null) {
        event.getHook().editOriginal(messages.get("queue.reply.config.need-arg")).queue();
        return;
      }
      request =
          new ConfigureQueueRequest(
              guildId,
              manageServer,
              propose == null ? null : propose.getAsInt(),
              bump == null ? null : bump.getAsInt(),
              null);
    } else if ("announce".equals(subcommand)) {
      long channelId = event.getOption("channel").getAsChannel().getIdLong();
      request =
          new ConfigureQueueRequest(guildId, manageServer, null, null, ChannelOp.set(channelId));
    } else {
      request = new ConfigureQueueRequest(guildId, manageServer, null, null, ChannelOp.off());
    }

    try {
      QueueConfigResult result = configureQueueService.configure(request);
      event.getHook().editOriginal(render(result)).queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }

  private String render(QueueConfigResult result) {
    String announcement =
        result.announcementChannelId() == null
            ? messages.get("queue.reply.config.channel-unset")
            : messages.get("queue.reply.config.channel-set", result.announcementChannelId());
    return messages.get(
        "queue.reply.config.updated", result.proposeCost(), result.bumpCost(), announcement);
  }
}
