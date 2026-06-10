package bot.infrastructure.persistence.coin;

import static org.assertj.core.api.Assertions.assertThat;

import bot.domain.coin.GuildCoinConfig;
import bot.support.AbstractPostgresIntegrationTest;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the per-server config adapter against real Postgres: defaults, full + partial upsert.
 */
class JpaGuildCoinConfigAdapterTest extends AbstractPostgresIntegrationTest {

  @Autowired private JpaGuildCoinConfigAdapter adapter;

  @Test
  void absentGuildDefaultsToCapTwelveAndNoRole() {
    long guild = uid();

    GuildCoinConfig config = adapter.get(guild);

    assertThat(config.cap()).isEqualTo(12);
    assertThat(config.moderatorRoleId()).isNull();
    assertThat(config.hasModeratorRole()).isFalse();
  }

  @Test
  void upsertStoresRoleAndCap() {
    long guild = uid();

    adapter.upsert(guild, 555L, 100);

    GuildCoinConfig config = adapter.get(guild);
    assertThat(config.moderatorRoleId()).isEqualTo(555L);
    assertThat(config.cap()).isEqualTo(100);
  }

  @Test
  void partialUpsertLeavesTheOtherSettingUnchanged() {
    long guild = uid();
    adapter.upsert(guild, 555L, 100);

    adapter.upsert(guild, null, 50); // cap only
    assertThat(adapter.get(guild).moderatorRoleId()).isEqualTo(555L);
    assertThat(adapter.get(guild).cap()).isEqualTo(50);

    adapter.upsert(guild, 777L, null); // role only
    assertThat(adapter.get(guild).moderatorRoleId()).isEqualTo(777L);
    assertThat(adapter.get(guild).cap()).isEqualTo(50);
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
