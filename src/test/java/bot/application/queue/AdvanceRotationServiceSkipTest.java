package bot.application.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import bot.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exercises {@code AdvanceRotationService.skip} against real Postgres: exactly one pop with {@code
 * now} as the clock baseline (the same deterministic body as the weekly advance), an empty-queue
 * skip designating an empty week, and the announcement assembled when a channel is configured.
 */
class AdvanceRotationServiceSkipTest extends AbstractPostgresIntegrationTest {

  @Autowired private AdvanceRotationService service;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void skipPopsExactlyOneGameWithNowAsTheClockBaseline() {
    long guild = uid();
    long a = uid();
    long b = uid();
    long slotA = insertSlot(guild, a, 1);
    insertSlot(guild, b, 2);
    setupRotation(guild, daysAgo(2), 1); // current week 1

    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    AdvanceResult result = service.skip(guild, now);

    assertThat(result.advancesApplied()).isEqualTo(1);
    assertThat(currentSlot(guild)).isEqualTo(slotA);
    assertThat(currentWeek(guild)).isEqualTo(2);
    assertThat(queuedMembers(guild)).containsExactly(b);
    assertThat(designationSlot(guild, 2)).isEqualTo(slotA);
    assertThat(lastPopAt(guild)).isCloseTo(now, within(2, ChronoUnit.SECONDS));
    assertThat(cooldownOf(guild, a)).isEqualTo(1); // B was still waiting at the pop
  }

  @Test
  void skipOnEmptyQueueDesignatesAnEmptyWeek() {
    long guild = uid();
    setupRotation(guild, daysAgo(2), 1);

    AdvanceResult result = service.skip(guild, Instant.now());

    assertThat(result.advancesApplied()).isEqualTo(1);
    assertThat(designationSlot(guild, 2)).isNull();
    assertThat(currentSlot(guild)).isNull();
    assertThat(result.finalAnnouncement()).isEmpty();
  }

  @Test
  void skipAssemblesAnnouncementWhenChannelConfigured() {
    long guild = uid();
    long a = uid();
    insertSlot(guild, a, 1);
    configureChannel(guild, uid());
    setupRotation(guild, daysAgo(2), 1);

    AdvanceResult result = service.skip(guild, Instant.now());

    assertThat(result.finalAnnouncement()).isPresent();
    assertThat(result.finalAnnouncement().get().currentGameName()).isEqualTo("Game" + a);
  }

  // --- setup helpers ---

  private long insertSlot(long guild, long member, int position) {
    return jdbc.queryForObject(
        "INSERT INTO queue_entry (guild_id, proposer_member_id, status, position,"
            + " game_identity, game_name, coins_spent, propose_interaction_id)"
            + " VALUES (?, ?, 'QUEUED', ?, ?, ?, 1, ?) RETURNING id",
        Long.class,
        guild,
        member,
        position,
        "name:game" + member,
        "Game" + member,
        uid());
  }

  private void setupRotation(long guild, Instant lastPop, int week) {
    jdbc.update(
        "INSERT INTO queue_rotation_state (guild_id, current_slot_id, current_week_number,"
            + " last_pop_at) VALUES (?, NULL, ?, ?)",
        guild,
        week,
        OffsetDateTime.ofInstant(lastPop, ZoneOffset.UTC));
  }

  private void configureChannel(long guild, long channelId) {
    jdbc.update(
        "INSERT INTO guild_queue_config (guild_id, propose_cost, bump_cost, announcement_channel_id)"
            + " VALUES (?, 1, 1, ?)",
        guild,
        channelId);
  }

  // --- queries ---

  private List<Long> queuedMembers(long guild) {
    return jdbc.queryForList(
        "SELECT proposer_member_id FROM queue_entry WHERE guild_id = ? AND status = 'QUEUED'"
            + " ORDER BY position",
        Long.class,
        guild);
  }

  private Long designationSlot(long guild, int week) {
    return jdbc.queryForObject(
        "SELECT slot_id FROM weekly_designation WHERE guild_id = ? AND week_number = ?",
        Long.class,
        guild,
        week);
  }

  private Long currentSlot(long guild) {
    return jdbc.queryForObject(
        "SELECT current_slot_id FROM queue_rotation_state WHERE guild_id = ?", Long.class, guild);
  }

  private int currentWeek(long guild) {
    Integer w =
        jdbc.queryForObject(
            "SELECT current_week_number FROM queue_rotation_state WHERE guild_id = ?",
            Integer.class,
            guild);
    return w == null ? 0 : w;
  }

  private int cooldownOf(long guild, long member) {
    List<Integer> rows =
        jdbc.queryForList(
            "SELECT games_remaining FROM queue_cooldown WHERE guild_id = ? AND member_id = ?",
            Integer.class,
            guild,
            member);
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  private Instant lastPopAt(long guild) {
    OffsetDateTime t =
        jdbc.queryForObject(
            "SELECT last_pop_at FROM queue_rotation_state WHERE guild_id = ?",
            OffsetDateTime.class,
            guild);
    return t == null ? null : t.toInstant();
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
