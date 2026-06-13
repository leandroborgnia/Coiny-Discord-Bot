package bot.application.participation;

import java.util.Set;

/**
 * Request to change a server's participation configuration. The actor is authorized against the
 * server's configured coin-moderator role (Administrator bypasses), mirroring {@code
 * AdjustCoinsService}. Only the fields relevant to {@link Op} are read.
 */
public record ConfigureParticipationRequest(
    long guildId,
    long actorMemberId,
    Set<Long> actorRoleIds,
    boolean actorIsAdmin,
    Op op,
    Long channelId, // CHANNEL_ADD
    Integer minutesPerDrop, // RATE
    Integer coinsPerDrop, // RATE
    Boolean freeFirstProposal) { // FREE_PROPOSAL

  public enum Op {
    CHANNEL_ADD,
    CHANNEL_RESET,
    RATE,
    FREE_PROPOSAL
  }
}
