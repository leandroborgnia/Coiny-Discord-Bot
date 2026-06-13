package bot.application.participation;

import java.time.Instant;

/**
 * Request to accrue one qualifying member's participation time for a single sweep tick. The current
 * game gating is done by the sweep before this call, so the service only measures time and mints
 * drops. {@code now} is the tick instant (sampled once per sweep).
 */
public record AccrueParticipationRequest(long guildId, long memberId, Instant now) {}
