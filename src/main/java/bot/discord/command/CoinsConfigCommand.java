package bot.discord.command;

import bot.application.coin.CoinConfigResult;
import bot.application.coin.CoinConfigService;
import bot.application.coin.ConfigureCoinsRequest;
import bot.domain.DomainException;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /coins-config} handler (administrator-gated): sets the per-server moderator role
 * and/or balance cap. Defers first, delegates to {@link CoinConfigService}, renders the result.
 */
@Component
public class CoinsConfigCommand implements SlashCommandHandler {

  private final CoinConfigService configService;
  private final CoinMessages messages;

  public CoinsConfigCommand(CoinConfigService configService, CoinMessages messages) {
    this.configService = configService;
    this.messages = messages;
  }

  @Override
  public String name() {
    return "coins-config";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(
            name(), "Configure the coin-moderator role and balance cap for this server.")
        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
        .setGuildOnly(true)
        .addOptions(
            new OptionData(OptionType.ROLE, "role", "Role allowed to grant/deduct coins", false),
            new OptionData(OptionType.INTEGER, "cap", "Maximum balance a member may hold", false)
                .setMinValue(0));
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    Member member = event.getMember();
    boolean admin = member != null && member.hasPermission(Permission.ADMINISTRATOR);

    OptionMapping roleOption = event.getOption("role");
    OptionMapping capOption = event.getOption("cap");
    if (roleOption == null && capOption == null) {
      event.getHook().editOriginal(messages.get("coin.reply.config.need-arg")).queue();
      return;
    }
    Long roleId = roleOption == null ? null : roleOption.getAsRole().getIdLong();
    Integer cap = capOption == null ? null : capOption.getAsInt();

    try {
      CoinConfigResult result =
          configService.configure(
              new ConfigureCoinsRequest(event.getGuild().getIdLong(), admin, roleId, cap));
      String roleText =
          result.moderatorRoleId() == null
              ? messages.get("coin.reply.config.role-unset")
              : "<@&" + result.moderatorRoleId() + ">";
      event
          .getHook()
          .editOriginal(messages.get("coin.reply.config.updated", roleText, result.cap()))
          .queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }
}
