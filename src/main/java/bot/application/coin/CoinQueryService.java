package bot.application.coin;

import bot.application.coin.BalanceView.MovementSummary;
import bot.domain.coin.CoinLedgerPort;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.MovementRecord;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read path: a member's derived balance, the server cap, and their recent history. */
@Service
public class CoinQueryService {

  private final CoinLedgerPort ledgerPort;
  private final GuildCoinConfigPort configPort;

  public CoinQueryService(CoinLedgerPort ledgerPort, GuildCoinConfigPort configPort) {
    this.ledgerPort = ledgerPort;
    this.configPort = configPort;
  }

  @Transactional(readOnly = true)
  public BalanceView viewBalance(ViewBalanceRequest request) {
    int balance = ledgerPort.currentBalance(request.guildId(), request.memberId());
    int cap = configPort.get(request.guildId()).cap();
    List<MovementSummary> recent =
        ledgerPort
            .recentHistory(request.guildId(), request.memberId(), request.historyLimit())
            .stream()
            .map(CoinQueryService::toSummary)
            .toList();
    return new BalanceView(balance, cap, recent);
  }

  private static MovementSummary toSummary(MovementRecord m) {
    return new MovementSummary(
        m.type(),
        m.requested(),
        m.credited(),
        m.forfeited(),
        m.reason(),
        m.moderatorId(),
        m.createdAt());
  }
}
