package bot.discord.command;

import bot.application.coin.BalanceView;
import bot.application.coin.BalanceView.MovementSummary;
import bot.application.coin.CoinQueryService;
import bot.application.coin.ViewBalanceRequest;
import bot.domain.coin.AdjustmentType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thin {@code /balance} handler (open to all): shows the caller's own balance, the server cap, and
 * their recent history. Ephemeral — a member sees only their own standing. Defers first, delegates
 * to {@link CoinQueryService}, renders.
 */
@Component
public class BalanceCommand implements SlashCommandHandler {

  private final CoinQueryService coinQueryService;
  private final CoinMessages messages;
  private final int historyLimit;

  public BalanceCommand(
      CoinQueryService coinQueryService,
      CoinMessages messages,
      @Value("${coin.history.default-limit:10}") int historyLimit) {
    this.coinQueryService = coinQueryService;
    this.messages = messages;
    this.historyLimit = historyLimit;
  }

  @Override
  public String name() {
    return "balance";
  }

  @Override
  public CommandData commandData() {
    return Commands.slash(name(), "Show your coin balance and recent history in this server.")
        .setGuildOnly(true);
  }

  @Override
  public void handle(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();
    BalanceView view =
        coinQueryService.viewBalance(
            new ViewBalanceRequest(
                event.getGuild().getIdLong(), event.getUser().getIdLong(), historyLimit));
    event.getHook().editOriginal(render(view)).queue();
  }

  private String render(BalanceView view) {
    StringBuilder sb = new StringBuilder();
    sb.append(messages.get("coin.reply.balance.header", view.balance(), view.cap()));
    sb.append('\n');
    if (view.recent().isEmpty()) {
      sb.append(messages.get("coin.reply.balance.no-history"));
    } else {
      view.recent().forEach(m -> sb.append('\n').append(historyLine(m)));
    }
    return sb.toString();
  }

  String historyLine(MovementSummary m) {
    boolean credit = m.type() == AdjustmentType.GRANT || m.type() == AdjustmentType.PARTICIPATION;
    String suffix = "";
    if (credit && m.forfeited() > 0) {
      suffix += messages.get("coin.reply.history.forfeited", m.forfeited());
    }
    if (m.reason() != null && !m.reason().isBlank()) {
      suffix += messages.get("coin.reply.history.reason", m.reason());
    }
    if (m.type() == AdjustmentType.PARTICIPATION) {
      return messages.get("coin.reply.history.participation", m.credited(), suffix);
    }
    if (m.type() == AdjustmentType.GRANT) {
      return messages.get("coin.reply.history.grant", m.credited(), m.moderatorId(), suffix);
    }
    if (m.type() == AdjustmentType.SKIP_JAR) {
      return messages.get("coin.reply.history.skip-jar", m.requested(), suffix);
    }
    return messages.get("coin.reply.history.deduct", m.requested(), m.moderatorId(), suffix);
  }
}
