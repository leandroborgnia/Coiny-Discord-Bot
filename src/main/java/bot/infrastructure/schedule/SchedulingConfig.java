package bot.infrastructure.schedule;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduling so the weekly-rotation tick ({@code RotationScheduler}) can run
 * (feature 004). Guarded by {@code discord.enabled}: tests run with Discord disabled and therefore
 * schedule nothing, keeping {@code ./mvnw verify} free of background ticks. Uses spring-context's
 * scheduler — no new dependency.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SchedulingConfig {}
