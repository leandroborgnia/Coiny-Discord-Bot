package bot.application.participation;

/**
 * The server's participation configuration after a change: the number of designated channels and
 * the current rate and free-first-proposal toggle. Re-read from storage so the reply echoes the
 * effective state.
 */
public record ParticipationConfigResult(
    int designatedChannelCount, int minutesPerDrop, int coinsPerDrop, boolean freeFirstProposal) {}
