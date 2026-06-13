package bot.domain.coin;

/**
 * One signed posting of a movement. {@code signedAmount} is positive to credit the account and
 * negative to debit it. {@code memberId} is set iff {@code account == MEMBER}.
 */
public record PostingLine(LedgerAccount account, Long memberId, int signedAmount) {

  public PostingLine {
    boolean isMember = account == LedgerAccount.MEMBER;
    if (isMember == (memberId == null)) {
      throw new IllegalArgumentException("memberId must be set iff account is MEMBER");
    }
  }

  public static PostingLine member(long memberId, int signedAmount) {
    return new PostingLine(LedgerAccount.MEMBER, memberId, signedAmount);
  }

  public static PostingLine treasury(int signedAmount) {
    return new PostingLine(LedgerAccount.TREASURY, null, signedAmount);
  }

  public static PostingLine forfeit(int signedAmount) {
    return new PostingLine(LedgerAccount.FORFEIT, null, signedAmount);
  }

  /**
   * The per-guild queue pot — the balanced counter-party for queue spends/refunds (feature 004).
   */
  public static PostingLine pot(int signedAmount) {
    return new PostingLine(LedgerAccount.POT, null, signedAmount);
  }

  /**
   * The per-guild skip-jar pot — the balanced counter-party for a skip-jar contribution (feature
   * 006). Carries no member id and is never refunded.
   */
  public static PostingLine skipPot(int signedAmount) {
    return new PostingLine(LedgerAccount.SKIP_POT, null, signedAmount);
  }
}
