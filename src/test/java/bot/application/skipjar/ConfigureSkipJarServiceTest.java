package bot.application.skipjar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bot.application.skipjar.ConfigureRequest.Op;
import bot.domain.coin.GuildCoinConfigPort;
import bot.domain.coin.ModeratorNotAuthorizedException;
import bot.domain.coin.ModeratorRoleNotConfiguredException;
import bot.domain.skipjar.GuildSkipJarConfig;
import bot.domain.skipjar.SkipJarConfigPort;
import bot.support.AbstractPostgresIntegrationTest;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises skip-jar configuration against real Postgres: authorized floor/dwell/gate changes
 * persist and re-read; an Administrator bypasses the role; a missing/lacking role is refused and
 * changes nothing (quickstart #15, FR-017/SC-009).
 */
class ConfigureSkipJarServiceTest extends AbstractPostgresIntegrationTest {

  private static final long MOD_ROLE = 555L;

  @Autowired private ConfigureSkipJarService service;
  @Autowired private SkipJarConfigPort skipJarConfigPort;
  @Autowired private GuildCoinConfigPort guildCoinConfigPort;

  @Test
  void authorizedFloorDwellAndGateChangesPersist() {
    long guild = uid();
    guildCoinConfigPort.upsert(guild, MOD_ROLE, 12);

    SkipJarConfigResult floor =
        service.configure(
            new ConfigureRequest(guild, Op.FLOOR, Set.of(MOD_ROLE), false, 7, 0L, false));
    assertThat(floor.thresholdFloor()).isEqualTo(7);

    SkipJarConfigResult dwell =
        service.configure(
            new ConfigureRequest(guild, Op.DWELL, Set.of(MOD_ROLE), false, 0, 43200L, false));
    assertThat(dwell.dwellSeconds()).isEqualTo(43200L);

    SkipJarConfigResult gate =
        service.configure(
            new ConfigureRequest(guild, Op.GATE, Set.of(MOD_ROLE), false, 0, 0L, false));
    assertThat(gate.gateOn()).isFalse();

    GuildSkipJarConfig persisted = skipJarConfigPort.get(guild);
    assertThat(persisted.thresholdFloor()).isEqualTo(7);
    assertThat(persisted.dwell().toSeconds()).isEqualTo(43200L);
    assertThat(persisted.gateOn()).isFalse();
  }

  @Test
  void administratorBypassesTheRole() {
    long guild = uid();
    guildCoinConfigPort.upsert(guild, MOD_ROLE, 12);

    SkipJarConfigResult result =
        service.configure(new ConfigureRequest(guild, Op.FLOOR, Set.of(), true, 4, 0L, false));

    assertThat(result.thresholdFloor()).isEqualTo(4);
  }

  @Test
  void noConfiguredRoleFailsClosedAndChangesNothing() {
    long guild = uid(); // no coin config row → no moderator role

    assertThatThrownBy(
            () ->
                service.configure(
                    new ConfigureRequest(guild, Op.FLOOR, Set.of(MOD_ROLE), true, 9, 0L, false)))
        .isInstanceOf(ModeratorRoleNotConfiguredException.class);
    assertThat(skipJarConfigPort.get(guild).thresholdFloor()).isEqualTo(3); // default unchanged
  }

  @Test
  void callerWithoutRoleOrAdminIsRefusedAndChangesNothing() {
    long guild = uid();
    guildCoinConfigPort.upsert(guild, MOD_ROLE, 12);

    assertThatThrownBy(
            () ->
                service.configure(
                    new ConfigureRequest(guild, Op.FLOOR, Set.of(1L, 2L), false, 9, 0L, false)))
        .isInstanceOf(ModeratorNotAuthorizedException.class);
    assertThat(skipJarConfigPort.get(guild).thresholdFloor()).isEqualTo(3); // default unchanged
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
