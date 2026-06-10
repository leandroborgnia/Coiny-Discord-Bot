package bot.domain.coin;

/**
 * The moderator-chosen operation. Forfeiture is a consequence of a grant (recorded as ledger
 * entries), not a type a moderator selects.
 */
public enum AdjustmentType {
  GRANT,
  DEDUCTION
}
