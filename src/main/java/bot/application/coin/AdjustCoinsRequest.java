package bot.application.coin;

import bot.domain.coin.AdjustmentType;
import java.util.Set;

/** Request to grant or deduct a member's coins. {@code interactionId} is the at-most-once key. */
public record AdjustCoinsRequest(
    long guildId,
    long actorMemberId,
    Set<Long> actorRoleIds,
    boolean actorIsAdmin,
    long targetMemberId,
    AdjustmentType type,
    int amount,
    String reason,
    long interactionId) {}
