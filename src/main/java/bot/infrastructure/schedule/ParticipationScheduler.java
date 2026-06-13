package bot.infrastructure.schedule;

import bot.application.participation.AccrueParticipationRequest;
import bot.application.participation.AccrueParticipationService;
import bot.domain.participation.CurrentGamePort;
import bot.domain.participation.DesignatedChannelPort;
import bot.domain.queue.GameIdentity;
import bot.infrastructure.discord.VoiceParticipantsReader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives participation earning (US1). A fixed-delay tick (cadence from {@code
 * participation.sweep.tick}) and a one-shot startup primer both sample, from JDA's in-memory cache,
 * who is currently in a designated voice channel playing the guild's current game, then accrue each
 * qualifying member in their own transaction (per-bean call to {@link AccrueParticipationService}).
 *
 * <p>Guilds with no current game are skipped (FR-011); downtime simply produces no ticks, so there
 * is no retroactive credit (FR-023). One member's (or one guild's) failure never aborts the sweep.
 * Gated by {@code discord.enabled}, so tests schedule nothing and need no JDA. Mirrors {@code
 * RotationScheduler}.
 */
@Component
@ConditionalOnProperty(
    prefix = "discord",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ParticipationScheduler {

  private static final Logger log = LoggerFactory.getLogger(ParticipationScheduler.class);

  private final AccrueParticipationService accrueParticipationService;
  private final DesignatedChannelPort designatedChannelPort;
  private final CurrentGamePort currentGamePort;
  private final VoiceParticipantsReader voiceParticipantsReader;

  public ParticipationScheduler(
      AccrueParticipationService accrueParticipationService,
      DesignatedChannelPort designatedChannelPort,
      CurrentGamePort currentGamePort,
      VoiceParticipantsReader voiceParticipantsReader) {
    this.accrueParticipationService = accrueParticipationService;
    this.designatedChannelPort = designatedChannelPort;
    this.currentGamePort = currentGamePort;
    this.voiceParticipantsReader = voiceParticipantsReader;
  }

  @Scheduled(fixedDelayString = "${participation.sweep.tick}")
  public void tick() {
    sweep();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void sweepOnStartup() {
    sweep();
  }

  private void sweep() {
    Instant now = Instant.now();
    for (long guildId : designatedChannelPort.guildsWithChannels()) {
      try {
        Optional<GameIdentity> identity = currentGamePort.currentGameIdentity(guildId);
        if (identity.isEmpty()) {
          continue; // no current game → no earning (FR-011)
        }
        List<Long> channelIds = designatedChannelPort.list(guildId);
        Set<Long> qualifying =
            voiceParticipantsReader.qualifyingMembers(guildId, channelIds, identity.get());
        for (long memberId : qualifying) {
          try {
            accrueParticipationService.accrue(
                new AccrueParticipationRequest(guildId, memberId, now));
          } catch (RuntimeException e) {
            log.warn(
                "Participation accrual failed for member {} in guild {}: {}",
                memberId,
                guildId,
                e.toString());
          }
        }
      } catch (RuntimeException e) {
        log.warn("Participation sweep failed for guild {}: {}", guildId, e.toString());
      }
    }
  }
}
