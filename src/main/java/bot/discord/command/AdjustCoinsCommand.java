package bot.discord.command;

import bot.application.coin.AdjustCoinsRequest;
import bot.application.coin.AdjustCoinsResult;
import bot.application.coin.AdjustCoinsService;
import bot.domain.DomainException;
import bot.domain.coin.AdjustmentType;
import java.util.Set;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
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
 * Thin {@code /coins-adjust} handler (moderator-gated) with {@code grant}/{@code deduct}
 * subcommands. Defers first, parses the interaction, delegates to {@link AdjustCoinsService} (which
 * performs the authoritative configured-role check), and renders the outcome.
 */
@Component
public class AdjustCoinsCommand implements SlashCommandHandler {

  private final AdjustCoinsService adjustCoinsService;
  private final CoinMessages messages;

  public AdjustCoinsCommand(AdjustCoinsService adjustCoinsService, CoinMessages messages) {
    this.adjustCoinsService = adjustCoinsService;
    this.messages = messages;
  }

  @Override
  public String name() {
    return "coins-adjust";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(name(), "Grant or deduct a member's coins (moderator action).")
        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
        .setGuildOnly(true)
        .addSubcommands(
            new SubcommandData("grant", "Grant coins to a member").addOptions(adjustOptions()),
            new SubcommandData("deduct", "Deduct coins from a member").addOptions(adjustOptions()));
  }

  private static OptionData[] adjustOptions() {
    return new OptionData[] {
      new OptionData(OptionType.USER, "member", "The member to adjust", true),
      new OptionData(OptionType.INTEGER, "amount", "Whole number of coins (≥ 1)", true)
          .setMinValue(1),
      new OptionData(OptionType.STRING, "reason", "Why this adjustment was made", false)
    };
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();

    AdjustmentType type =
        "grant".equals(event.getSubcommandName()) ? AdjustmentType.GRANT : AdjustmentType.DEDUCTION;
    Set<Long> roleIds =
        event.getMember().getRoles().stream().map(Role::getIdLong).collect(Collectors.toSet());
    boolean admin = event.getMember().hasPermission(Permission.ADMINISTRATOR);
    User target = event.getOption("member").getAsUser();
    int amount = event.getOption("amount").getAsInt();
    OptionMapping reasonOption = event.getOption("reason");
    String reason = reasonOption == null ? null : reasonOption.getAsString();

    AdjustCoinsRequest request =
        new AdjustCoinsRequest(
            event.getGuild().getIdLong(),
            event.getUser().getIdLong(),
            roleIds,
            admin,
            target.getIdLong(),
            type,
            amount,
            reason,
            event.getIdLong());

    try {
      AdjustCoinsResult result = adjustCoinsService.adjust(request);
      event.getHook().editOriginal(render(result, type, target.getIdLong(), amount)).queue();
    } catch (DomainException e) {
      event.getHook().editOriginal(messages.error(e)).queue();
    }
  }

  private String render(AdjustCoinsResult result, AdjustmentType type, long targetId, int amount) {
    if (result.outcome() == AdjustCoinsResult.Outcome.DUPLICATE) {
      return messages.get("coin.reply.duplicate");
    }
    if (type == AdjustmentType.GRANT) {
      if (result.forfeitedAmount() > 0) {
        return messages.get(
            "coin.reply.granted.forfeited",
            result.creditedAmount(),
            targetId,
            result.forfeitedAmount(),
            result.newBalance(),
            result.cap());
      }
      return messages.get(
          "coin.reply.granted",
          result.creditedAmount(),
          targetId,
          result.newBalance(),
          result.cap());
    }
    return messages.get("coin.reply.deducted", amount, targetId, result.newBalance(), result.cap());
  }
}
