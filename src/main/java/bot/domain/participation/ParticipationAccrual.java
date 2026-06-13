package bot.domain.participation;

import java.time.Instant;

/**
 * Per-member accrual state: the {@code bankedSeconds} of qualifying time toward the next drop (the
 * unminted remainder) and {@code lastSampledAt}, the instant of the last accrual tick. A member
 * with no stored row reads as {@code (0, null)}; {@code lastSampledAt} stays null until the member
 * is first observed qualifying.
 *
 * <p>Pure domain type — no framework imports.
 */
public record ParticipationAccrual(
    long guildId, long memberId, long bankedSeconds, Instant lastSampledAt) {}
